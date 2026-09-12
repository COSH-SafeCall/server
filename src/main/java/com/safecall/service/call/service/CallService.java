package com.safecall.service.call.service;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.repository.*;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.call.api.CallDtos.*;
import com.safecall.service.call.gemini.*;
import com.safecall.service.call.repository.CallRepository;
import com.safecall.service.call.repository.CallRepository.*;
import com.safecall.service.common.crypto.*;
import com.safecall.service.common.error.*;
import com.safecall.service.user.repository.UserRepository;
@Service
@Transactional(isolation=Isolation.READ_COMMITTED,noRollbackFor=SessionInvalidException.class)
public class CallService {
	private final AuthTransactions authentication;private final AuthRepository auth;private final UserRepository users;private final CallRepository repository;
	private final SecretCrypto crypto;private final TransientKeys keys;private final JsonMapper mapper;private final Clock clock;
	private final GeminiClient gemini;private final GeminiSettings settings;private final PromptComposer composer;private final CallPolicy policy;
	public CallService(AuthTransactions authentication,AuthRepository auth,UserRepository users,CallRepository repository,SecretCrypto crypto,TransientKeys keys,JsonMapper mapper,
		Clock clock,GeminiClient gemini,GeminiSettings settings,PromptComposer composer,CallPolicy policy){
		this.authentication=authentication;this.auth=auth;this.users=users;this.repository=repository;this.crypto=crypto;this.keys=keys;
		this.mapper=mapper;this.clock=clock;this.gemini=gemini;this.settings=settings;this.composer=composer;this.policy=policy;
	}
	private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
	private byte[] hash(Object body){return crypto.hash("CALL_REQUEST",mapper.writeValueAsString(body));}
	private void match(byte[] a,byte[] b){if(!crypto.isEqual(a,b))throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);}
	private byte[] scope(Session s,UUID id){return crypto.idempotency("SESSION",s.id(),id==null?"COLLECTION":"CALL",id==null?"calls":id.toString());}
	private byte[] pageHash(String value){
		try{if(value==null||!value.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();byte[] b=Base64.getUrlDecoder().decode(value);
			if(b.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(b).equals(value))throw new IllegalArgumentException();return crypto.hashBytes("CALL_PAGE:v1",b);
		}catch(IllegalArgumentException ex){throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);}
	}
	private Call owned(Session s,UUID id,String page){Call c=repository.call(id,false);if(c==null||!c.sessionId().equals(s.id())||!crypto.isEqual(c.pageKeyHash(),pageHash(page)))throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);return repository.call(id,true);}
	private void eligible(Session s){
		if(s.step()!=Step.COMPLETE)throw new CustomException(ErrorCode.ONBOARDING_REQUIRED);
		if(s.userId()!=null){
			if(users.pending(s.userId(),"ACCOUNT")||users.pending(s.userId(),"AI_DATA"))throw new CustomException(ErrorCode.DATA_CLEANUP_PENDING);
			if(!repository.hasConsent(s.userId(),"PRIVACY_PROCESSING",now())||!repository.hasConsent(s.userId(),"AI_CALL",now()))throw new CustomException(ErrorCode.CONSENT_REQUIRED);
		}
	}
	private Replay replay(Session s,UUID id,String op,UUID key,byte[] hash){Replay r=auth.replay(scope(s,id),op,key);if(r!=null){match(r.requestHash(),hash);if(!r.status().equals("DONE"))throw new CustomException(ErrorCode.REQUEST_IN_PROGRESS,1);}return r;}
	private void remember(Session s,UUID target,String op,UUID key,byte[] hash,UUID result){auth.saveReplay(UUID.randomUUID(),s,scope(s,target),op,key,hash,result,null,null,now());}
	public CallView create(String cookie,String page,CreateCall body,UUID key){
		Session s=authentication.authenticated(cookie);byte[] pageHash=pageHash(page),hash=hash(body);Instant now=now();
		Replay r=replay(s,null,"CALL_CREATE",key,hash);if(r!=null)return expire(owned(s,r.resourceId(),page),now).view();
		Call prior=repository.byClient(s.id(),body.clientCallId());
		if(prior!=null){owned(s,prior.view().id(),page);match(repository.createHash(prior.view().id()),hash);remember(s,null,"CALL_CREATE",key,hash,prior.view().id());return expire(prior,now).view();}
		eligible(s);
		if(body.microphonePermission()!=Permission.GRANTED)throw new CustomException(ErrorCode.MICROPHONE_REQUIRED);
		if(!repository.scenarios().contains(body.scenarioCode())||!Set.of("FATHER","MOTHER","FRIEND").contains(body.counterpartCode()))throw new CustomException(ErrorCode.INVALID_CALL_OPTION);
		if(body.startMode()==StartMode.QUICK&&!body.counterpartCode().equals("FATHER"))throw new CustomException(ErrorCode.INVALID_QUICK_START);
		Call open=repository.open(s.id());if(open!=null&&!expire(open,now).isTerminal())throw new CustomException(ErrorCode.CALL_ALREADY_OPEN);
		if(!gemini.isConfigured())throw new CustomException(ErrorCode.PROMPT_NOT_READY);
		Prompt prompt=prompt(null,body.scenarioCode(),body.counterpartCode(),now);
		if(!policy.isValidated(prompt.model()))throw new CustomException(ErrorCode.GEMINI_VALIDATION_REQUIRED);
		var composed=composer.compose(prompt,auth.user(s.userId(),false),now);limits(s,now);
		Instant expiry=min(now.plusSeconds(Math.min(settings.connectionSeconds(),policy.modelMaxSeconds())),s.expiresAt());
		UUID id=UUID.randomUUID();repository.insert(id,s,body,prompt.releaseId(),composed.isDemographicApplied(),composed.isGenderAddressApplied(),now,expiry,pageHash);
		repository.event(id,UUID.randomUUID(),hash,"CREATED","CREATED",now,now);repository.preparing(id);repository.event(id,UUID.randomUUID(),hash,"PREPARING","PREPARING",now,now);
		remember(s,null,"CALL_CREATE",key,hash,id);return repository.call(id,false).view();
	}
	public CallView get(String cookie,String page,UUID id){Session s=authentication.authenticated(cookie);return expire(owned(s,id,page),now()).view();}
	public Object connection(String cookie,String page,UUID id,UUID grantId){
		Session s=authentication.authenticated(cookie);Call call=expire(owned(s,id,page),now());Grant g=repository.grant(id,grantId);
		if(g==null)throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
		if(g.status().equals("UNKNOWN"))throw new SessionInvalidException(ErrorCode.CONNECTION_ISSUE_UNKNOWN);
		if(g.status().equals("USED"))throw new SessionInvalidException(ErrorCode.CONNECTION_ALREADY_USED);
		if(g.newSessionExpiresAt()!=null&&!g.newSessionExpiresAt().isAfter(now()))throw new SessionInvalidException(ErrorCode.CONNECTION_GRANT_EXPIRED);
		active(call);eligible(s);
		if(Set.of("PENDING","ISSUING").contains(g.status()))return new IssuingView("ISSUING",250);
		if(!g.status().equals("READY"))throw new CustomException(ErrorCode.CALL_TERMINAL);
		Prompt p=prompt(call.releaseId(),call.view().scenarioCode(),call.view().counterpartCode(),now());
		String token=new String(crypto.open(keys.read(g.keyRef()),"GEMINI_GRANT:"+g.id(),g.tokenCipher()),StandardCharsets.UTF_8);
		return new ConnectionView(g.id(),g.generation(),g.purpose(),"READY",token,p.model(),p.apiVersion(),p.voice(),List.of("AUDIO"),new SessionResumption(true),g.newSessionExpiresAt(),g.expiresAt(),1);
	}
	public CallView event(String cookie,String page,UUID id,CallEvent body,UUID key){
		Session s=authentication.authenticated(cookie);Call call=owned(s,id,page);byte[] hash=hash(body);Instant now=now();
		if(replay(s,id,"CALL_EVENT",key,hash)!=null)return expire(call,now).view();
		byte[] prior=repository.eventHash(id,body.eventId());if(prior!=null){match(prior,hash);remember(s,id,"CALL_EVENT",key,hash,id);return expire(call,now).view();}
		call=expire(call,now);active(call);eligible(s);
		boolean connected=body.type()==EventType.CONNECTED||body.type()==EventType.RESUMED;
		if(connected!=(body.grantId()!=null)||(body.type()==EventType.FAILED)!=(body.errorCode()!=null))throw new CustomException(ErrorCode.INVALID_REQUEST);
		Grant g=repository.grant(id);String state=call.view().state();
		if(connected&&!g.id().equals(body.grantId()))throw new CustomException(ErrorCode.STALE_CONNECTION_GENERATION);
		if(body.expectedVersion()!=call.view().version())throw new CustomException(ErrorCode.VERSION_CONFLICT);
		switch(body.type()){
			case CONNECTED,RESUMED -> {
				boolean initial=body.type()==EventType.CONNECTED;
				if(!state.equals(initial?"PREPARING":"ACTIVE")||!g.status().equals("READY")||!g.purpose().equals(initial?"INITIAL":"RESUME"))throw new CustomException(ErrorCode.CALL_TRANSITION_INVALID);
				repository.used(g,now);repository.transition(id,state,now);
			}
			case RINGING_SHOWN -> {if(!state.equals("PREPARING")||!g.status().equals("USED")||!g.purpose().equals("INITIAL"))throw new CustomException(ErrorCode.CALL_TRANSITION_INVALID);state="RINGING";repository.transition(id,state,now);}
			case ANSWERED -> {if(!state.equals("RINGING"))throw new CustomException(ErrorCode.CALL_TRANSITION_INVALID);state="ACTIVE";repository.transition(id,state,now);}
			case GO_AWAY,CONNECTION_INTERRUPTED -> {if(!state.equals("ACTIVE"))throw new CustomException(ErrorCode.CALL_TRANSITION_INVALID);repository.transition(id,state,now);}
			case FAILED -> {state="FAILED";repository.end(id,state,body.errorCode().name(),now);}
		}
		repository.event(id,body.eventId(),hash,body.type().name(),state,body.occurredAt(),now);remember(s,id,"CALL_EVENT",key,hash,id);return repository.call(id,false).view();
	}
	public HeartbeatView heartbeat(String cookie,String page,UUID id){
		Session s=authentication.authenticated(cookie);Instant now=now();Call c=expire(owned(s,id,page),now);active(c);eligible(s);
		Instant lease=min(now.plusSeconds(policy.leaseSeconds()),min(c.view().expiresAt(),s.expiresAt()));repository.heartbeat(id,now,lease);return new HeartbeatView(c.view().state(),lease,c.view().expiresAt());
	}
	public CallView end(String cookie,String page,UUID id,EndCall body,UUID key){
		Session s=authentication.authenticated(cookie);Call c=owned(s,id,page);byte[] hash=hash(body);
		if(replay(s,id,"CALL_END",key,hash)!=null)return expire(c,now()).view();
		c=expire(c,now());if(!c.isTerminal()){repository.end(id,"ENDED",body.reason().name(),now());repository.event(id,UUID.randomUUID(),hash,"ENDED","ENDED",body.occurredAt(),now());}
		remember(s,id,"CALL_END",key,hash,id);return repository.call(id,false).view();
	}
	public GrantView renew(String cookie,String page,UUID id,RenewalRequest body,UUID key){
		Session s=authentication.authenticated(cookie);Call c=owned(s,id,page);byte[] hash=hash(body);
		c=expire(c,now());active(c);eligible(s);
		Replay r=replay(s,id,"CALL_RENEWAL",key,hash);
		if(r!=null){Grant g=repository.grant(id,r.resourceId());if(g==null)throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);return new GrantView(g.id(),g.generation(),g.purpose(),g.status());}
		Grant previous=repository.grant(id);
		if(!c.view().state().equals("ACTIVE"))throw new CustomException(ErrorCode.CALL_TRANSITION_INVALID);
		if(!previous.id().equals(body.previousGrantId()))throw new CustomException(ErrorCode.RENEWAL_ALREADY_REQUESTED);
		if(repository.resumeCount(id)>=c.view().maxResumeAttempts())throw new CustomException(ErrorCode.RESUME_BUDGET_EXHAUSTED);
		if(!previous.status().equals("USED"))throw new CustomException(ErrorCode.RENEWAL_ALREADY_REQUESTED);
		Instant minute=now().truncatedTo(ChronoUnit.MINUTES);byte[] rate=crypto.hash("RENEW_RATE",id.toString());
		if(repository.rate(rate,"SESSION","CALL_RENEWAL",minute,60,false)>=policy.renewalMinuteLimit())throw new CustomException(ErrorCode.RATE_LIMITED,60);
		repository.rate(rate,"SESSION","CALL_RENEWAL",minute,60,true);
		UUID grant=repository.newGrant(id,previous.generation()+1,"RESUME",now());repository.transition(id,"ACTIVE",now());
		repository.event(id,UUID.randomUUID(),hash,"RESUME_REQUESTED","ACTIVE",now(),now());remember(s,id,"CALL_RENEWAL",key,hash,grant);
		return new GrantView(grant,previous.generation()+1,"RESUME","PENDING");
	}
	private void active(Call c){if(c.isTerminal())throw new SessionInvalidException(ErrorCode.CALL_TERMINAL);}
	private void terminate(Call c,String state,String reason,Instant now){if(c.isTerminal())return;repository.end(c.view().id(),state,reason,now);repository.event(c.view().id(),UUID.randomUUID(),crypto.hash("SERVER_EVENT",reason),state,state,now,now);}
	private String failure(Grant g){return g.purpose().equals("RESUME")?"RESUMPTION_FAILED":"CONNECTION_FAILED";}
	private Call expire(Call c,Instant now){
		if(c.isTerminal())return c;Grant g=repository.grant(c.view().id());
		if(g.status().equals("ISSUING")&&!g.createdAt().plusSeconds(policy.issueTimeoutSeconds()).isAfter(now)){terminate(c,"FAILED",failure(g),now);repository.markUnknown(g.id());}
		else if(!c.view().expiresAt().isAfter(now))terminate(c,"ENDED","DURATION_LIMIT",now);
		else if(!c.view().leaseExpiresAt().isAfter(now))terminate(c,"ENDED","SESSION_EXPIRED",now);
		else if(g.status().equals("READY")&&!g.newSessionExpiresAt().isAfter(now))terminate(c,"FAILED",failure(g),now);
		return repository.call(c.view().id(),false);
	}
	private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
	private Prompt prompt(UUID release,String scenario,String counterpart,Instant now) {
		var all=repository.prompts(release,now);
		validatePrompts(all,repository.scenarios());
		return all.stream().filter(p->p.scenario().equals(scenario)&&p.counterpart().equals(counterpart)).findFirst()
			.orElseThrow(()->new CustomException(ErrorCode.PROMPT_NOT_READY));
	}
	static void validatePrompts(List<Prompt> all,Set<String> scenarios) {
		Set<String> combinations=new HashSet<>();
		for(Prompt p:all) {
			if(!p.model().matches("models/[A-Za-z0-9._-]{1,160}") || !p.apiVersion().equals("v1beta") || !p.voice().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
				|| p.base().isBlank() || p.safety().isBlank() || p.demographic().isBlank() || p.guest().isBlank()
				|| p.base().length()+p.safety().length()+p.demographic().length()+p.guest().length()>32000
				|| !p.demographic().contains("{age}") || !p.demographic().contains("{gender}"))throw new CustomException(ErrorCode.PROMPT_NOT_READY);
			combinations.add(p.scenario()+":"+p.counterpart());
		}
		Set<String> expected=new HashSet<>();
		for(String s:scenarios)for(String c:List.of("FATHER","MOTHER","FRIEND"))expected.add(s+":"+c);
		if(all.size()!=12 || expected.size()!=12 || !combinations.equals(expected))throw new CustomException(ErrorCode.PROMPT_NOT_READY);
	}

	private void limits(Session s,Instant now){
		Instant minute=now.truncatedTo(ChronoUnit.MINUTES),day=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		byte[] session=crypto.hash("CALL_RATE",s.id().toString()),daily=s.userId()==null?session:crypto.hash("CALL_RATE",s.userId().toString());String kind=s.userId()==null?"SESSION":"USER";
		if(repository.rate(session,"SESSION","CALL_MINUTE",minute,60,false)>=policy.minuteLimit())throw new CustomException(ErrorCode.RATE_LIMITED,(int)(minute.plusSeconds(60).getEpochSecond()-now.getEpochSecond()));
		if(repository.rate(daily,kind,"CALL_DAY",day,86400,false)>=(s.userId()==null?policy.guestDailyLimit():policy.memberDailyLimit()))throw new CustomException(ErrorCode.RATE_LIMITED,(int)(day.plusSeconds(86400).getEpochSecond()-now.getEpochSecond()));
		repository.rate(session,"SESSION","CALL_MINUTE",minute,60,true);repository.rate(daily,kind,"CALL_DAY",day,86400,true);
	}
	private Call lockForWorker(UUID id){Call c=repository.call(id,false);if(c==null||auth.lockSession(c.sessionId())==null)return null;return repository.call(id,true);}
	private boolean workerEligible(Session s,Call c){
		if(!s.status().equals("ACTIVE")||!s.expiresAt().isAfter(now())){terminate(c,"ENDED","SESSION_EXPIRED",now());return false;}
		User u=auth.user(s.userId(),false);if(s.userId()!=null&&(u==null||u.status().equals("DELETION_PENDING"))){terminate(c,"ENDED","DATA_DELETION",now());return false;}
		try{eligible(s);return true;}catch(CustomException ex){terminate(c,"ENDED","CONSENT_WITHDRAWN",now());return false;}
	}
	public GeminiClient.IssueRequest claim(UUID id){
		Call c=lockForWorker(id);if(c==null)return null;c=expire(c,now());if(c.isTerminal())return null;Session s=auth.session(c.sessionId());
		if(!workerEligible(s,c))return null;Grant g=repository.grant(id);if(!g.status().equals("PENDING"))return null;
		try{
			Prompt p=prompt(c.releaseId(),c.view().scenarioCode(),c.view().counterpartCode(),now());String instruction=null;
			if(g.purpose().equals("INITIAL")){var composed=composer.compose(p,auth.user(s.userId(),false),now());instruction=composed.instruction();repository.flags(id,composed.isDemographicApplied(),composed.isGenderAddressApplied());}
			if(!repository.claim(g.id(),now()))return null;
			return new GeminiClient.IssueRequest(p.model(),p.apiVersion(),p.voice(),instruction,min(now().plusSeconds(settings.newSessionSeconds()),c.view().expiresAt()),c.view().expiresAt(),g.id(),g.purpose());
		}catch(CustomException ex){terminate(c,"FAILED",failure(g),now());return null;}
	}
	public void finish(UUID id,GeminiClient.IssueRequest request,String token,Boolean unknown){
		Call c=lockForWorker(id);if(c==null)return;c=expire(c,now());Grant g=repository.grant(id);
		if(c.isTerminal()||!g.id().equals(request.grantId())||!g.status().equals("ISSUING")||!workerEligible(auth.session(c.sessionId()),c))return;
		if(token==null){terminate(c,"FAILED",failure(g),now());if(Boolean.TRUE.equals(unknown))repository.markUnknown(g.id());return;}
		if(!request.newSessionExpiresAt().isAfter(now())||!request.expiresAt().isAfter(now())){terminate(c,"FAILED",failure(g),now());return;}
		String ref=keys.create(g.id());byte[] cipher=crypto.seal(keys.read(ref),"GEMINI_GRANT:"+g.id(),token.getBytes(StandardCharsets.UTF_8));
		repository.ready(g.id(),cipher,ref,request.newSessionExpiresAt(),request.expiresAt(),now());
	}
	public void reap(UUID id){Call c=lockForWorker(id);if(c!=null){c=expire(c,now());if(!c.isTerminal())workerEligible(auth.session(c.sessionId()),c);}}
}

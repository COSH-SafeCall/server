package com.safecall.service.user.service;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.api.AuthDtos.ProfileView;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.crypto.*;
import com.safecall.service.common.error.*;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.repository.UserRepository;
import com.safecall.service.user.repository.UserRepository.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED, noRollbackFor=SessionInvalidException.class)
public class UserTransactions {
	private final AuthTransactions authentication;
	private final AuthRepository auth;
	private final UserRepository repository;
	private final UserKeyStore keys;
	private final SecretCrypto crypto;
	private final JsonMapper mapper;
	private final Clock clock;
	public UserTransactions(AuthTransactions authentication, AuthRepository auth, UserRepository repository,
		UserKeyStore keys, SecretCrypto crypto, JsonMapper mapper, Clock clock) {
		this.authentication=authentication; this.auth=auth; this.repository=repository; this.keys=keys;
		this.crypto=crypto; this.mapper=mapper; this.clock=clock;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	private Session member(String access) { return authentication.member(access,false); }
	private User user(Session session) { return auth.user(session.userId(),false); }
	private byte[] seal(byte[] key, UUID owner, String field, String value) {
		return crypto.seal(key,owner+":"+field,value==null ? null : value.getBytes(StandardCharsets.UTF_8));
	}
	private String open(byte[] key, UUID owner, String field, byte[] value) {
		return value==null ? null : new String(crypto.open(key,owner+":"+field,value),StandardCharsets.UTF_8);
	}
	private void version(long current, long expected) {
		if (current!=expected) throw new CustomException(ErrorCode.VERSION_CONFLICT);
	}
	public ProfileView profile(String access) { return profile(user(member(access))); }
	private ProfileView profile(User user) {
		byte[] key=keys.read(user.keyRef());
		String gender=open(key,user.id(),"gender",user.genderCipher());
		String birth=open(key,user.id(),"birthDate",user.birthDateCipher());
		return new ProfileView(user.id(),open(key,user.id(),"name",user.nameCipher()),gender==null ? "UNKNOWN" : gender,
			birth==null ? null : LocalDate.parse(birth),open(key,user.id(),"phone",user.phoneCipher()),
			user.genderSource(),user.birthDateSource(),user.confirmedAt(),user.version());
	}
	public ProfileView updateProfile(String access, ProfileRequest request) {
		User user=user(member(access));
		version(user.version(),request.expectedVersion());
		String name=UserValues.text(request.name(),50), phone=UserValues.phone(request.phone());
		LocalDate birth=UserValues.birthDate(request.birthDate(),LocalDate.ofInstant(now(),ZoneOffset.UTC));
		String gender=request.gender()==Gender.UNKNOWN ? null : request.gender().name();
		if (gender!=null || birth!=null) {
			if (repository.pending(user.id(),"AI_DATA")) throw new CustomException(ErrorCode.DATA_CLEANUP_PENDING);
			if (repository.wasWithdrawn(user.id(),"AI_CALL") && !valid(user.id(),"AI_CALL")) throw new CustomException(ErrorCode.CONSENT_REQUIRED);
		}
		byte[] hash=crypto.hash("PHONE_MATCH:"+user.id(),phone);
		if (repository.contacts(user.id()).stream().anyMatch(c -> crypto.isEqual(hash,c.phoneHash()))) throw new CustomException(ErrorCode.CONTACT_PHONE_DUPLICATE);
		ProfileView old=profile(user);
		String genderSource=gender==null ? "UNKNOWN" : Objects.equals(gender,old.gender()) && "KAKAO".equals(old.genderSource()) ? "KAKAO" : "USER_CONFIRMED";
		String birthSource=birth==null ? "UNKNOWN" : Objects.equals(birth,old.birthDate()) && "KAKAO".equals(old.birthDateSource()) ? "KAKAO" : "USER_CONFIRMED";
		byte[] key=keys.read(user.keyRef());
		repository.updateProfile(user.id(),seal(key,user.id(),"name",name),seal(key,user.id(),"gender",gender),
			seal(key,user.id(),"birthDate",birth==null ? null : birth.toString()),seal(key,user.id(),"phone",phone),hash,genderSource,birthSource,now());
		return profile(auth.user(user.id(),false));
	}
	public Items<ContactView> contacts(String access) {
		User user=user(member(access));
		byte[] key=keys.read(user.keyRef());
		return new Items<>(repository.contacts(user.id()).stream().map(c -> contactView(c,key)).toList());
	}
	private ContactView contactView(Contact c, byte[] key) {
		return new ContactView(c.id(),c.slot(),open(key,c.id(),"name",c.name()),open(key,c.id(),"relationship",c.relationship()),open(key,c.id(),"phone",c.phone()),c.version());
	}
	private Contact contact(UUID user, UUID id) {
		return repository.contacts(user).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new CustomException(ErrorCode.CONTACT_NOT_FOUND));
	}
	private ContactRequest normalized(ContactRequest request) {
		return new ContactRequest(UserValues.text(request.name(),50),UserValues.text(request.relationship(),30),UserValues.phone(request.phone()));
	}
	private byte[] contactHash(UUID user, UUID excluded, String phone) {
		byte[] hash=crypto.hash("PHONE_MATCH:"+user,phone);
		if (crypto.isEqual(hash,repository.ownPhoneHash(user)) || repository.contacts(user).stream()
			.anyMatch(c -> !c.id().equals(excluded) && crypto.isEqual(hash,c.phoneHash()))) throw new CustomException(ErrorCode.CONTACT_PHONE_DUPLICATE);
		return hash;
	}
	public ContactView createContact(String access, ContactRequest input, UUID key) {
		Session session=member(access); User user=user(session); ContactRequest request=normalized(input);
		Replay replay=replay(session,"U08",key,request);
		if (replay!=null) return contactView(contact(user.id(),replay.resourceId()),keys.read(user.keyRef()));
		byte[] hash=contactHash(user.id(),null,request.phone());
		List<Contact> existing=repository.contacts(user.id());
		if (existing.size()>=2) throw new CustomException(ErrorCode.CONTACT_LIMIT_REACHED);
		int slot=existing.stream().anyMatch(c -> c.slot()==1) ? 2 : 1;
		UUID id=UUID.randomUUID(); byte[] secret=keys.read(user.keyRef());
		repository.createContact(user.id(),id,slot,seal(secret,id,"name",request.name()),seal(secret,id,"relationship",request.relationship()),seal(secret,id,"phone",request.phone()),hash,now());
		save(session,"U08",key,request,id,null);
		return contactView(contact(user.id(),id),secret);
	}
	public ContactView updateContact(String access, UUID id, ContactUpdate input) {
		User user=user(member(access)); Contact existing=contact(user.id(),id); version(existing.version(),input.expectedVersion());
		ContactRequest request=normalized(new ContactRequest(input.name(),input.relationship(),input.phone()));
		byte[] hash=contactHash(user.id(),id,request.phone()), secret=keys.read(user.keyRef());
		repository.updateContact(user.id(),id,seal(secret,id,"name",request.name()),seal(secret,id,"relationship",request.relationship()),seal(secret,id,"phone",request.phone()),hash,now());
		return contactView(contact(user.id(),id),secret);
	}
	public void deleteContact(String access, UUID id, long expected, UUID key) {
		Session session=member(access); var request=Map.of("contactId",id,"expectedVersion",expected);
		if (replay(session,"U10",key,request)!=null) return;
		Contact contact=contact(session.userId(),id); version(contact.version(),expected);
		repository.deleteContact(session.userId(),id); save(session,"U10",key,request,id,null);
	}
	public SettingView settings(String access) { return repository.settings(member(access).userId()); }
	public SettingView updateSettings(String access, SettingRequest request) {
		UUID user=member(access).userId(); version(repository.settings(user).version(),request.expectedVersion());
		try { repository.updateSettings(user,request.incomingAlertMode(),now()); return repository.settings(user); }
		catch (org.springframework.dao.DataAccessException exception) { throw new CustomException(ErrorCode.SETTINGS_SAVE_FAILED); }
	}
	public Items<ConsentView> consents(String access) { return consentViews(member(access).userId()); }
	private Items<ConsentView> consentViews(UUID user) {
		return new Items<>(repository.documents(true).stream().filter(Document::isConsent).map(d -> {
			Event latest=repository.latest(user,d.code());
			return new ConsentView(d.code(),d.version(),latest==null ? "NOT_DECIDED" : latest.action(),latest==null ? null : latest.version(),
				DocumentService.purpose(d.code()),latest==null ? null : latest.recordedAt(),latest!=null && latest.action().equals("GRANTED") && latest.version()==d.version());
		}).toList());
	}
	private boolean valid(UUID user, String code) {
		return consentViews(user).items().stream().anyMatch(c -> c.code().equals(code) && c.isValid());
	}
	private Document document(String code, int version) {
		DocumentService.consentCode(code);
		return repository.documents(true).stream().filter(d -> d.code().equals(code) && d.version()==version && d.isConsent()).findFirst()
			.orElseThrow(() -> new CustomException(ErrorCode.CONSENT_VERSION_CHANGED));
	}
	public Items<ConsentView> decide(String access, DecisionsRequest input, UUID key) {
		Session session=member(access); UUID user=session.userId();
		var request=new DecisionsRequest(input.decisions().stream().sorted(Comparator.comparing(Decision::code)).toList());
		if (request.decisions().stream().map(Decision::code).distinct().count()!=request.decisions().size()) throw new CustomException(ErrorCode.VALIDATION_FAILED);
		if (replay(session,"U05",key,request)!=null) return consentViews(user);
		for (Decision decision:request.decisions()) {
			document(decision.code(),decision.version());
			if (decision.action()==DecisionAction.GRANTED && (repository.pending(user,"ACCOUNT") || repository.pending(user,"AI_DATA"))) throw new CustomException(ErrorCode.DATA_CLEANUP_PENDING);
			Event previous=repository.latest(user,decision.code());
			if (decision.action()==DecisionAction.DECLINED && previous!=null && previous.action().equals("GRANTED")) throw new CustomException(ErrorCode.WITHDRAWAL_REQUIRED);
		}
		for (Decision decision:request.decisions()) repository.decision(user,decision.code(),decision.version(),decision.action().name(),now());
		save(session,"U05",key,request,user,null); return consentViews(user);
	}
	public WithdrawalView withdraw(String access, String code, WithdrawRequest body, UUID key) {
		Session session=authentication.member(access,true); UUID user=session.userId();
		var request=Map.of("code",code,"version",body.version());
		Replay replay=replay(session,"U06",key,request);
		if (replay!=null) {
			if (replay.responseCipher()==null || replay.responseExpiresAt()==null || !replay.responseExpiresAt().isAfter(now())) throw new CustomException(ErrorCode.DELETION_RECEIPT_EXPIRED);
			return mapper.readValue(crypto.openResponse(replay.id().toString(),replay.responseCipher()),WithdrawalView.class);
		}
		if ("DELETION_PENDING".equals(user(session).status())) throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		document(code,body.version());
		String scope=switch(code) { case "PRIVACY_PROCESSING" -> "ACCOUNT"; case "AI_CALL" -> "AI_DATA"; default -> "LOCATION_DATA"; };
		if (repository.pending(user,scope)) throw new CustomException(ErrorCode.DATA_CLEANUP_PENDING);
		Instant now=now(); String receiptToken=crypto.randomToken();
		var receipt=new DeletionReceipt(UUID.randomUUID(),scope,"PENDING",receiptToken,now.plusSeconds(86400),now.plusSeconds(30*86400));
		repository.decision(user,code,body.version(),"WITHDRAWN",now);
		if (!scope.equals("LOCATION_DATA")) {
			for (UUID id:repository.sessions(user)) {
				Session locked=auth.lockSession(id);
				if (locked!=null) auth.endCalls(locked,now,"CONSENT_WITHDRAWN",crypto.hash("SERVER_EVENT","CONSENT_WITHDRAWN"));
			}
		}
		repository.deletion(user,receipt,crypto.hash("DELETION_RECEIPT",receiptToken),now);
		var response=new WithdrawalView(code,"WITHDRAWN",receipt);
		save(session,"U06",key,request,receipt.id(),response); return response;
	}
	private byte[] scope(Session session) { return crypto.hash("IDEMPOTENCY","USER:"+session.userId()); }
	private byte[] hash(Object request) {
		// Map의 JVM별 순회 순서가 재시작 후 멱등 요청 해시를 바꾸지 않게 한다.
		return crypto.hash("REQUEST",mapper.writeValueAsString(request instanceof Map<?,?> map ? new TreeMap<>(map) : request));
	}
	private Replay replay(Session session,String operation,UUID key,Object request) {
		Replay replay=auth.replay(scope(session),operation,key);
		if (replay!=null) {
			if (!crypto.isEqual(hash(request),replay.requestHash())) throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
			if (!"DONE".equals(replay.status())) throw new CustomException(ErrorCode.REQUEST_IN_PROGRESS,1);
		}
		return replay;
	}
	private void save(Session session,String operation,UUID key,Object request,UUID resource,Object response) {
		UUID id=UUID.randomUUID(); Instant now=now();
		auth.saveReplay(id,session,scope(session),operation,key,hash(request),resource,
			response==null ? null : crypto.sealResponse(id.toString(),mapper.writeValueAsBytes(response)),response==null ? null : now.plusSeconds(60),now);
	}
}

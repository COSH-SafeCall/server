package com.safecall.service.auth.service;
import static com.safecall.service.auth.repository.AuthRepository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.repository.*;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.auth.kakao.KakaoClient.KakaoIdentity;
import com.safecall.service.common.crypto.*;
import com.safecall.service.common.error.*;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.repository.UserRepository;
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class OAuthTransactions {
	private final JdbcTemplate jdbc; private final AuthTransactions auth; private final AuthRepository accounts;
	private final UserRepository users; private final SecretCrypto crypto; private final TransientKeys keys;
	private final WebPolicy policy; private final Clock clock;
	public OAuthTransactions(JdbcTemplate jdbc,AuthTransactions auth,AuthRepository accounts,UserRepository users,SecretCrypto crypto,TransientKeys keys,WebPolicy policy,Clock clock) {
		this.jdbc=jdbc;this.auth=auth;this.accounts=accounts;this.users=users;this.crypto=crypto;this.keys=keys;this.policy=policy;this.clock=clock;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	public record Attempt(UUID id,UUID sessionId,Purpose purpose,Instant createdAt,Instant expiresAt,String redirectUri,List<Decision> decisions) {}
	public record Start(String state,Instant expiresAt) { @Override public String toString(){return "Start[redacted]";} }
	public Start start(String cookie,AuthorizationRequest request) {
		Session s=auth.basic(cookie,false);
		if(policy.clientId().isBlank())throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
		if(request.purpose()==Purpose.REAUTH) {
			if(s.userId()==null)throw new CustomException(ErrorCode.LOGIN_REQUIRED);
			if(request.decisions()!=null)throw new CustomException(ErrorCode.INVALID_REQUEST);
		} else validateDecisions(request.decisions());
		Instant now=now(),expiry=now.plusSeconds(policy.anonymousSeconds());
		if(s.expiresAt().isBefore(expiry))expiry=s.expiresAt();
		String state=crypto.randomToken(); UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO `oauthAttempt` (`id`,`sessionId`,`stateHash`,`purpose`,`redirectUri`,`createdAt`,`expiresAt`) VALUES (?,?,?,?,?,?,?)",
			bin(id),bin(s.id()),crypto.hash("OAUTH_STATE",state),request.purpose().name(),policy.redirectUri(),time(now),time(expiry));
		if(request.decisions()!=null)for(Decision d:request.decisions())jdbc.update("INSERT INTO `oauthConsent` (`attemptId`,`documentCode`,`documentVersion`,`action`,`recordedAt`) VALUES (?,?,?,?,?)",
			bin(id),d.code(),d.version(),d.action().name(),time(now));
		return new Start(state,expiry);
	}
	private void validateDecisions(List<Decision> decisions) {
		if(decisions==null || decisions.size()!=3 || !decisions.stream().map(Decision::code).collect(java.util.stream.Collectors.toSet()).equals(Set.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING")))
			throw new CustomException(ErrorCode.INVALID_CONSENT);
		var docs=users.documents(true);
		for(Decision d:decisions) {
			if(!d.code().equals("LOCATION_PROCESSING") && d.action()!=DecisionAction.GRANTED)throw new CustomException(ErrorCode.CONSENT_REQUIRED);
			if(docs.stream().noneMatch(doc->doc.code().equals(d.code()) && doc.version()==d.version() && doc.isConsent() && !doc.publishedAt().isAfter(now())))
				throw new CustomException(ErrorCode.INVALID_CONSENT);
		}
	}
	private List<Decision> decisions(UUID id) {
		return jdbc.query("SELECT * FROM `oauthConsent` WHERE `attemptId`=? ORDER BY `documentCode`",(r,n)->new Decision(r.getString("documentCode"),r.getInt("documentVersion"),DecisionAction.valueOf(r.getString("action"))),bin(id));
	}
	public Attempt claim(String cookie,String state) {
		Session s=auth.basic(cookie,false);
		if(state==null || !state.matches("[A-Za-z0-9_-]{43}"))throw new CustomException(ErrorCode.ACCESS_DENIED);
		var rows=jdbc.query("SELECT * FROM `oauthAttempt` WHERE `stateHash`=? AND `sessionId`=? AND `status`='PENDING' AND `expiresAt`>? FOR UPDATE",
			(r,n)->new Attempt(bytesId(r.getBytes("id")),s.id(),Purpose.valueOf(r.getString("purpose")),r.getObject("createdAt",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getObject("expiresAt",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getString("redirectUri"),null),crypto.hash("OAUTH_STATE",state),bin(s.id()),time(now()));
		if(rows.isEmpty())throw new CustomException(ErrorCode.ACCESS_DENIED);
		Attempt a=rows.getFirst(); var decisions=decisions(a.id());
		// Claim first and commit failure without exchanging a code when consent is invalid.
		if(a.purpose()==Purpose.LOGIN)try { validateDecisions(decisions); } catch(CustomException ex) {
			jdbc.update("UPDATE `oauthAttempt` SET `status`='FAILED',`completedAt`=? WHERE `id`=?",time(now()),bin(a.id())); return null;
		}
		jdbc.update("UPDATE `oauthAttempt` SET `status`='EXCHANGING' WHERE `id`=? AND `status`='PENDING'",bin(a.id()));
		return new Attempt(a.id(),s.id(),a.purpose(),a.createdAt(),a.expiresAt(),a.redirectUri(),decisions);
	}
	private static UUID bytesId(byte[] b) { var buffer=java.nio.ByteBuffer.wrap(b);return new UUID(buffer.getLong(),buffer.getLong()); }
	public void fail(UUID id) { jdbc.update("UPDATE `oauthAttempt` SET `status`='FAILED',`completedAt`=? WHERE `id`=? AND `status`='EXCHANGING'",time(now()),bin(id)); }
	public AuthTransactions.SessionResult complete(Attempt a,String cookie,KakaoIdentity identity) {
		byte[] subjectHash=crypto.hash("KAKAO_SUBJECT",identity.subject());
		if(accounts.isDeletionPending(subjectHash))throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		User user=accounts.userBySubject(subjectHash);
		Session previous=auth.basic(cookie,false);
		if(!previous.id().equals(a.sessionId()) || !a.expiresAt().isAfter(now()) || accounts.isDeletionPending(subjectHash))throw new CustomException(ErrorCode.ACCESS_DENIED);
		var pending=jdbc.queryForList("SELECT `id` FROM `oauthAttempt` WHERE `id`=? AND `status`='EXCHANGING' FOR UPDATE",bin(a.id()));
		if(pending.isEmpty())throw new CustomException(ErrorCode.ACCESS_DENIED);
		if(a.purpose()==Purpose.REAUTH) {
			if(user==null || !user.id().equals(previous.userId()))throw new CustomException(ErrorCode.REAUTH_ACCOUNT_MISMATCH);
		} else {
			validateDecisions(a.decisions());
			if(user!=null) {
				if(users.pending(user.id(),"ACCOUNT") || users.pending(user.id(),"AI_DATA") || users.pending(user.id(),"LOCATION_DATA"))throw new CustomException(ErrorCode.DATA_CLEANUP_PENDING);
				for(Decision d:a.decisions()) {
					var latest=users.latest(user.id(),d.code());
					if(latest!=null && latest.action().equals("WITHDRAWN") && !latest.recordedAt().isBefore(a.createdAt()))throw new CustomException(ErrorCode.CONSENT_REQUIRED);
				}
			} else {
				UUID id=UUID.randomUUID(); String ref=keys.create(id); byte[] secret=keys.read(ref);
				// Consent and initial profile are committed together, only after the OAuth consent recheck.
				accounts.createUser(id,subjectHash,seal(secret,id,"subject",identity.subject()),ref,
					seal(secret,id,"name",identity.name()),seal(secret,id,"gender",identity.gender()),seal(secret,id,"birthDate",identity.birthDate()==null?null:identity.birthDate().toString()),
					seal(secret,id,"phone",identity.phone()),identity.phone()==null?null:crypto.hash("PHONE_MATCH:"+id,identity.phone()),now());
				user=accounts.user(id,false);
			}
			for(Decision d:a.decisions()) {
				var latest=users.latest(user.id(),d.code());
				if(d.code().equals("LOCATION_PROCESSING") && d.action()==DecisionAction.DECLINED && latest!=null && latest.action().equals("GRANTED") && latest.version()==d.version()) {
					users.decision(user.id(),d.code(),d.version(),"WITHDRAWN",now());
					String receipt=crypto.randomToken();
					users.deletion(user.id(),new DeletionReceipt(UUID.randomUUID(),"LOCATION_DATA","PENDING",receipt,now().plusSeconds(86400),now().plusSeconds(2592000)),crypto.hash("DELETION_RECEIPT",receipt),now());
				} else users.decision(user.id(),d.code(),d.version(),d.action().name(),now());
			}
			// Existing profile, especially withdrawn demographics, is never silently restored on login.
		}
		if(user.status().equals("DELETION_PENDING"))throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		auth.end(previous,"REVOKED","LOGOUT");
		Instant now=now();
		var result=auth.issue(user.id(),"KAKAO",a.purpose()==Purpose.REAUTH?previous.step():Step.PROFILE,a.purpose()==Purpose.REAUTH?now:null,now.plusSeconds(policy.memberSeconds()));
		jdbc.update("UPDATE `oauthAttempt` SET `status`='SUCCEEDED',`completedAt`=? WHERE `id`=?",time(now),bin(a.id()));
		return result;
	}
	private byte[] seal(byte[] key,UUID id,String field,String text) { return crypto.seal(key,id+":"+field,text==null?null:text.getBytes(StandardCharsets.UTF_8)); }
}

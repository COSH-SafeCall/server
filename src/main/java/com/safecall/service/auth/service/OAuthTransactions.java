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
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class OAuthTransactions {
	private final JdbcTemplate jdbc; private final AuthTransactions auth; private final AuthRepository accounts;
	private final SecretCrypto crypto; private final TransientKeys keys;
	private final WebPolicy policy; private final Clock clock;
	public OAuthTransactions(JdbcTemplate jdbc,AuthTransactions auth,AuthRepository accounts,SecretCrypto crypto,TransientKeys keys,WebPolicy policy,Clock clock) {
		this.jdbc=jdbc;this.auth=auth;this.accounts=accounts;this.crypto=crypto;this.keys=keys;this.policy=policy;this.clock=clock;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	public record Attempt(UUID id,UUID sessionId,Purpose purpose,Instant createdAt,Instant expiresAt,String redirectUri) {}
	public record Start(String state,Instant expiresAt) { @Override public String toString(){return "Start[redacted]";} }
	public Start start(String cookie,AuthorizationRequest request) {
		Session s=auth.basic(cookie,false);
		if(policy.clientId().isBlank())throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
		if(request.purpose()==Purpose.REAUTH && s.userId()==null)throw new CustomException(ErrorCode.LOGIN_REQUIRED);
		Instant now=now(),expiry=now.plusSeconds(policy.anonymousSeconds());
		if(s.expiresAt().isBefore(expiry))expiry=s.expiresAt();
		String state=crypto.randomToken(); UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO `oauthAttempt` (`id`,`sessionId`,`stateHash`,`purpose`,`redirectUri`,`createdAt`,`expiresAt`) VALUES (?,?,?,?,?,?,?)",
			bin(id),bin(s.id()),crypto.hash("OAUTH_STATE",state),request.purpose().name(),policy.redirectUri(),time(now),time(expiry));
		return new Start(state,expiry);
	}
	public Attempt claim(String cookie,String state) {
		Session s=auth.basic(cookie,false);
		if(state==null || !state.matches("[A-Za-z0-9_-]{43}"))throw new CustomException(ErrorCode.ACCESS_DENIED);
		var rows=jdbc.query("SELECT * FROM `oauthAttempt` WHERE `stateHash`=? AND `sessionId`=? AND `status`='PENDING' AND `expiresAt`>? FOR UPDATE",
			(r,n)->new Attempt(bytesId(r.getBytes("id")),s.id(),Purpose.valueOf(r.getString("purpose")),r.getObject("createdAt",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getObject("expiresAt",LocalDateTime.class).toInstant(ZoneOffset.UTC),r.getString("redirectUri")),crypto.hash("OAUTH_STATE",state),bin(s.id()),time(now()));
		if(rows.isEmpty())throw new CustomException(ErrorCode.ACCESS_DENIED);
		Attempt a=rows.getFirst();
		jdbc.update("UPDATE `oauthAttempt` SET `status`='EXCHANGING' WHERE `id`=? AND `status`='PENDING'",bin(a.id()));
		return a;
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
		} else if(user==null) {
			UUID id=UUID.randomUUID(); String ref=keys.create(id); byte[] secret=keys.read(ref);
			// OAuth establishes identity only. The user saves profile and consent after login.
			accounts.createUser(id,subjectHash,seal(secret,id,"subject",identity.subject()),ref,
				null,null,null,null,null,now());
			user=accounts.user(id,false);
		}

		if(user.status().equals("DELETION_PENDING"))throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		auth.end(previous,"REVOKED","LOGOUT");
		Instant now=now();
		var result=auth.issue(user.id(),"KAKAO",a.purpose()==Purpose.REAUTH?now:null,now.plusSeconds(policy.memberSeconds()));
		jdbc.update("UPDATE `oauthAttempt` SET `status`='SUCCEEDED',`completedAt`=? WHERE `id`=?",time(now),bin(a.id()));
		return result;
	}
	private byte[] seal(byte[] key,UUID id,String field,String text) { return crypto.seal(key,id+":"+field,text==null?null:text.getBytes(StandardCharsets.UTF_8)); }
}

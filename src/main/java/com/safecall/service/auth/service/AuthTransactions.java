package com.safecall.service.auth.service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.repository.*;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.*;
@Service
@Transactional(isolation=Isolation.READ_COMMITTED,noRollbackFor=SessionInvalidException.class)
public class AuthTransactions {
	private final AuthRepository repository;
	private final SecretCrypto crypto;
	private final Clock clock;
	private final WebPolicy policy;
	public AuthTransactions(AuthRepository repository,SecretCrypto crypto,Clock clock,WebPolicy policy) {
		this.repository=repository; this.crypto=crypto; this.clock=clock; this.policy=policy;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	public record SessionResult(SessionView view,String cookie) {
		@Override public String toString() { return "SessionResult[redacted]"; }
	}
	public SessionResult issue(UUID user,String kind,Instant expiry) {
		Instant now=now(); UUID id=UUID.randomUUID(); String cookie=crypto.randomToken();
		repository.createSession(id,user,kind,crypto.hash("WEB_SESSION",cookie),crypto.hash("CSRF",crypto.csrf(id)),now,expiry);
		Session session=repository.session(id);
		if(Set.of("MEMBER","GUEST").contains(kind))repository.observe(session,"AUTH","AUTH_SUCCEEDED",true,now);
		return new SessionResult(view(session),cookie);
	}
	public SessionResult bootstrap(String cookie) {
		Session s=lookup(cookie);
		if(s!=null && s.status().equals("ACTIVE") && s.expiresAt().isAfter(now())) {
			return new SessionResult(view(s),null);
		}
		if(s!=null && s.status().equals("ACTIVE")) end(s,"EXPIRED","SESSION_EXPIRED");
		return issue(null,"ANONYMOUS",now().plusSeconds(policy.anonymousSeconds()));
	}
	private Session lookup(String cookie) {
		if(cookie==null || !cookie.matches("[A-Za-z0-9_-]{43}"))return null;
		Session s=repository.byCookie(crypto.hash("WEB_SESSION",cookie));
		return s==null?null:repository.lockSession(s.id());
	}
	public Session basic(String cookie,boolean deletion) {
		Session s=lookup(cookie);
		if(s==null || !s.status().equals("ACTIVE"))throw new CustomException(ErrorCode.SESSION_EXPIRED);
		if(!s.expiresAt().isAfter(now())) { end(s,"EXPIRED","SESSION_EXPIRED"); throw new SessionInvalidException(ErrorCode.SESSION_EXPIRED); }
		User user=repository.user(s.userId(),false);
		if(s.userId()!=null && (user==null || !deletion && user.status().equals("DELETION_PENDING"))) throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		return s;
	}
	public Session authenticated(String cookie) {
		Session s=basic(cookie,false);
		if(s.kind().equals("ANONYMOUS"))throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
		return s;
	}
	public Session member(String cookie,boolean deletion) {
		Session s=basic(cookie,deletion); if(s.userId()==null)throw new CustomException(ErrorCode.LOGIN_REQUIRED); return s;
	}
	public void verifyCsrf(String cookie,String token,boolean logout) {
		Session s=lookup(cookie);
		if(s==null || (!logout || !s.status().equals("REVOKED")) && (!s.status().equals("ACTIVE") || !s.expiresAt().isAfter(now())))
			throw new CustomException(ErrorCode.SESSION_EXPIRED);
		if(token==null || !crypto.isEqual(s.csrfHash(),crypto.hash("CSRF",token)))throw new CustomException(ErrorCode.CSRF_INVALID);
	}
	public SessionResult guest(String cookie) {
		Session s=basic(cookie,false);
		if(s.kind().equals("MEMBER"))throw new CustomException(ErrorCode.ALREADY_AUTHENTICATED);
		if(s.kind().equals("GUEST"))return new SessionResult(view(s),null);
		end(s,"REVOKED","LOGOUT"); return issue(null,"GUEST",now().plusSeconds(policy.guestSeconds()));
	}
	public void logout(String cookie) {
		if(cookie==null)return;
		Session s=lookup(cookie); if(s==null)throw new CustomException(ErrorCode.SESSION_EXPIRED);
		if(s.status().equals("REVOKED"))return;
		if(s.kind().equals("ANONYMOUS"))throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
		end(s,"REVOKED","LOGOUT");
	}
	public void end(Session s,String status,String reason) { repository.endSession(s,now(),status,reason,crypto.hash("SERVER_EVENT",reason)); }
	public void expire(UUID id) {
		Session s=repository.lockSession(id);
		if(s!=null && s.status().equals("ACTIVE") && !s.expiresAt().isAfter(now()))end(s,"EXPIRED","SESSION_EXPIRED");
	}
	private SessionView view(Session s) { return new SessionView(s.kind(),s.kind().equals("MEMBER"),crypto.csrf(s.id()),s.expiresAt(),s.userId()==null?"LOGIN_ONLY":"MEMBER"); }
}

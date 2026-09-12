package com.safecall.service.auth.service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
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
	private final JsonMapper mapper;
	public AuthTransactions(AuthRepository repository,SecretCrypto crypto,Clock clock,WebPolicy policy,JsonMapper mapper) {
		this.repository=repository; this.crypto=crypto; this.clock=clock; this.policy=policy; this.mapper=mapper;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	public record SessionResult(SessionView view,String cookie) {
		@Override public String toString() { return "SessionResult[redacted]"; }
	}
	public SessionResult issue(UUID user,String kind,Step step,Instant verified,Instant expiry) {
		Instant now=now(); UUID id=UUID.randomUUID(); String cookie=crypto.randomToken();
		repository.createSession(id,user,kind,step,crypto.hash("WEB_SESSION",cookie),crypto.hash("CSRF",crypto.csrf(id)),now,expiry,verified);
		return new SessionResult(view(repository.session(id)),cookie);
	}
	public SessionResult bootstrap(String cookie) {
		Session s=lookup(cookie);
		if(s!=null && s.status().equals("ACTIVE") && s.expiresAt().isAfter(now())) {
			return new SessionResult(view(s),null);
		}
		if(s!=null && s.status().equals("ACTIVE")) end(s,"EXPIRED","SESSION_EXPIRED");
		return issue(null,"ANONYMOUS",Step.ENTRY,null,now().plusSeconds(policy.anonymousSeconds()));
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
		if(s.kind().equals("KAKAO"))throw new CustomException(ErrorCode.ALREADY_AUTHENTICATED);
		if(s.kind().equals("GUEST"))return new SessionResult(view(s),null);
		end(s,"REVOKED","LOGOUT"); return issue(null,"GUEST",Step.PERMISSIONS,null,now().plusSeconds(policy.guestSeconds()));
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
	public void requireSensitive(Session session) {
		Instant verified=session.sensitiveVerifiedAt();
		if(verified==null || verified.isAfter(now()) || !verified.plusSeconds(policy.sensitiveSeconds()).isAfter(now()))
			throw new CustomException(ErrorCode.REAUTHENTICATION_REQUIRED);
	}
	private SessionView view(Session s) { return new SessionView(s.kind(),s.kind().equals("KAKAO"),crypto.csrf(s.id()),s.expiresAt(),s.step(),s.userId()==null?"LOGIN_ONLY":"MEMBER"); }
	public OnboardingView onboarding(String cookie) { Session s=authenticated(cookie); return new OnboardingView(s.step(),s.version()); }
	public OnboardingView advance(String cookie,AdvanceRequest r,UUID key) {
		Session s=authenticated(cookie);
		if(r.step()==Step.CONSENTS || r.step()==Step.ENTRY)throw new CustomException(ErrorCode.BUSINESS_VALIDATION_FAILED);
		boolean notice=r.step()==Step.PERMISSIONS || r.step()==Step.SOS_GUIDE;
		if(notice ? !Boolean.TRUE.equals(r.isNoticeReviewed()) : r.isNoticeReviewed()!=null)throw new CustomException(ErrorCode.INVALID_REQUEST);
		if((r.step()==Step.MESSAGE_TEST)!=(r.testDecision()!=null))throw new CustomException(ErrorCode.INVALID_REQUEST);
		byte[] scope=crypto.idempotency("SESSION",s.id(),"COLLECTION","onboarding"), hash=crypto.hash("REQUEST",mapper.writeValueAsString(r));
		Replay replay=repository.replay(scope,"ONBOARDING_ADVANCE",key);
		if(replay!=null) {
			if(!crypto.isEqual(hash,replay.requestHash()))throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
			return new OnboardingView(s.step(),s.version());
		}
		if(s.step()!=r.step())throw new CustomException(ErrorCode.ONBOARDING_STEP_MISMATCH);
		if(s.step()==Step.COMPLETE)return new OnboardingView(s.step(),s.version());
		if(s.version()!=r.expectedVersion())throw new CustomException(ErrorCode.VERSION_CONFLICT);
		if(s.userId()!=null && !repository.missingConsents(s.userId()).isEmpty())throw new CustomException(ErrorCode.CONSENT_REQUIRED);
		Step next=switch(s.step()) {
			case PROFILE -> {
				User user=repository.user(s.userId(),false);
				if(user==null || user.nameCipher()==null || user.phoneCipher()==null || user.confirmedAt()==null)throw new CustomException(ErrorCode.PROFILE_REQUIRED);
				yield user.status().equals("ACTIVE")?Step.PERMISSIONS:Step.CONTACTS;
			}
			case CONTACTS -> Step.PERMISSIONS;
			case PERMISSIONS -> Step.SOS_GUIDE;
			case SOS_GUIDE -> s.userId()==null?Step.COMPLETE:Step.MESSAGE_TEST;
			case MESSAGE_TEST -> Step.COMPLETE;
			default -> throw new CustomException(ErrorCode.ONBOARDING_STEP_MISMATCH);
		};
		repository.advance(s,next,now());
		repository.saveReplay(UUID.randomUUID(),s,scope,"ONBOARDING_ADVANCE",key,hash,s.id(),null,null,now());
		Session updated=repository.session(s.id()); return new OnboardingView(updated.step(),updated.version());
	}
}

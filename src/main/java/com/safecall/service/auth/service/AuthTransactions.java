package com.safecall.service.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.kakao.KakaoClient.KakaoIdentity;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.crypto.UserKeyStore;
import com.safecall.service.common.error.*;

/** 외부 OAuth 호출을 제외한 인증 상태 변경을 하나의 MySQL 트랜잭션으로 처리한다. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = SessionInvalidException.class)
public class AuthTransactions {
	private final AuthRepository repository;
	private final TokenService tokens;
	private final SecretCrypto crypto;
	private final UserKeyStore keys;
	private final JsonMapper mapper;
	private final Clock clock;

	public AuthTransactions(AuthRepository repository, TokenService tokens, SecretCrypto crypto,
		UserKeyStore keys, JsonMapper mapper, Clock clock) {
		this.repository = repository; this.tokens = tokens; this.crypto = crypto;
		this.keys = keys; this.mapper = mapper; this.clock = clock;
	}
	private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
	private byte[] requestHash(Object body) { return crypto.hash("REQUEST", mapper.writeValueAsString(body)); }
	private byte[] scope(String value) { return crypto.hash("IDEMPOTENCY", value); }
	private byte[] accessHash(String value) { return crypto.hash("ACCESS", value); }
	private byte[] refreshHash(String value) { return crypto.hash("REFRESH", value); }

	public AuthResponse guest(GuestRequest request, UUID key, String access) {
		try {
			byte[] bootstrap = Base64.getUrlDecoder().decode(request.bootstrapSecret());
			if (bootstrap.length < 32) throw new IllegalArgumentException();
		} catch (IllegalArgumentException exception) { throw new CustomException(ErrorCode.VALIDATION_FAILED); }
		return login(request.installationId(), request.appVersion(), request.osVersion(),
			request.currentRefreshToken(), access, null, key, request,
			scope("GUEST:" + request.installationId() + ":" + request.bootstrapSecret()), "A01");
	}

	public AuthResponse kakao(KakaoRequest request, KakaoIdentity identity, UUID key, String access) {
		byte[] subjectHash = crypto.hash("KAKAO_SUBJECT", identity.subject());
		// 삭제로 appUser가 사라진 뒤에도 외부 unlink 작업과 새 가입이 충돌하지 않게 차단한다.
		if (repository.isDeletionPending(subjectHash)) throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		User user = repository.userBySubject(subjectHash);
		// 계정 잠금을 기다리는 사이 삭제가 커밋되었을 수 있으므로 다시 확인한다.
		if (repository.isDeletionPending(subjectHash)) throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		if (user == null) {
			UUID id = UUID.randomUUID();
			String keyRef = keys.create(id);
			// 회원 생성 경쟁/롤백으로 쓰이지 않은 개인 키를 남기지 않는다.
			org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
				new org.springframework.transaction.support.TransactionSynchronization() {
					@Override public void afterCompletion(int status) {
						if (status != STATUS_COMMITTED) keys.discard(keyRef);
					}
				});
			byte[] encryptionKey = keys.read(keyRef);
			Instant now = now();
			repository.createUser(id, subjectHash, seal(encryptionKey, id, "subject", identity.subject()), keyRef,
				seal(encryptionKey, id, "name", identity.name()), seal(encryptionKey, id, "gender", identity.gender()),
				seal(encryptionKey, id, "birthDate", identity.birthDate() == null ? null : identity.birthDate().toString()),
				seal(encryptionKey, id, "phone", identity.phone()),
				identity.phone() == null ? null : crypto.hash("PHONE_MATCH:" + id, identity.phone()), now);
			user = repository.user(id, false);
		}
		if ("DELETION_PENDING".equals(user.status())) throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		// 기존 프로필은 재로그인으로 덮어쓰지 않는다. 특히 철회된 AI 인구통계 정보를 복원하지 않는다.
		return login(request.installationId(), request.appVersion(), request.osVersion(),
			request.currentRefreshToken(), access, user, key, request,
			scope("KAKAO:" + request.installationId() + ":" + identity.subject()), "A02");
	}

	private AuthResponse login(UUID installationId, String appVersion, String osVersion, String refresh,
		String access, User user, UUID key, Object request, byte[] scopeHash, String operation) {
		Instant now = now();
		UUID installation = repository.installation(crypto.hash("INSTALLATION", installationId.toString()), appVersion, osVersion, now);
		Replay replay = repository.replay(scopeHash, operation, key);
		byte[] hash = requestHash(request);
		if (replay != null) {
			match(replay, hash);
			Session session = repository.session(replay.ownerSessionId());
			Credential credential = session == null ? null : repository.currentCredential(session.id());
			if (session == null || !"ACTIVE".equals(session.status()) || !session.expiresAt().isAfter(now)
				|| credential == null || credential.generation() != 1 || !credential.id().equals(replay.resourceId())) {
				throw new CustomException(ErrorCode.AUTH_REPLAY_EXPIRED);
			}
			return replay(replay, AuthResponse.class, ErrorCode.AUTH_REPLAY_EXPIRED, now);
		}
		Session previous = repository.activeSession(installation);
		if (previous != null) {
			if (!previous.expiresAt().isAfter(now)) {
				end(previous, now, "EXPIRED", "SESSION_EXPIRED");
			} else {
				if (!isOwner(previous, access, refresh, now)) throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
				end(previous, now, "REVOKED", "LOGOUT");
			}
		}
		repository.updateInstallation(installation, appVersion, osVersion, now);
		Instant expiry = user == null ? now.plusSeconds(86400) : tokens.memberExpiry(now);
		UUID sessionId = UUID.randomUUID();
		repository.createSession(sessionId, installation, user == null ? null : user.id(), now, expiry);
		Session session = repository.session(sessionId);
		Tokens issued = tokens.issue(sessionId, expiry, now);
		UUID credentialId = saveCredential(issued, 1, now);
		AuthResponse response = new AuthResponse(issued, sessionView(session), profile(user));
		save(session, scopeHash, operation, key, hash, credentialId, response, now.plusSeconds(60), now);
		repository.observe(session, "AUTH", "AUTH_SUCCEEDED", true, now);
		return response;
	}

	private boolean isOwner(Session session, String access, String refresh, Instant now) {
		Credential current = repository.currentCredential(session.id());
		if (current == null) return false;
		if (refresh != null && current.refreshExpiresAt().isAfter(now)
			&& crypto.isEqual(refreshHash(refresh), current.refreshHash())) return true;
		if (access == null) return false;
		try {
			return tokens.verify(access).equals(session.id()) && current.accessExpiresAt().isAfter(now)
				&& crypto.isEqual(accessHash(access), current.accessHash());
		} catch (CustomException exception) { return false; }
	}

	public Tokens refresh(RefreshRequest request, UUID key) {
		Instant now = now();
		byte[] hash = refreshHash(request.refreshToken());
		Credential previous = repository.credentialByRefresh(hash);
		if (previous == null) throw new CustomException(ErrorCode.SESSION_EXPIRED);
		Session session = repository.lockSession(previous.sessionId());
		ensureActive(session, now);
		previous = repository.credential(previous.id());
		byte[] scopeHash = scope("REFRESH:" + session.id() + ":" + Base64.getEncoder().encodeToString(hash));
		Replay replay = repository.replay(scopeHash, "A03", key);
		byte[] requestHash = requestHash(request);
		if (replay != null) {
			match(replay, requestHash);
			Credential current = repository.currentCredential(session.id());
			if (current == null || !current.id().equals(replay.resourceId())) throw new CustomException(ErrorCode.REFRESH_REPLAY_EXPIRED);
			return replay(replay, Tokens.class, ErrorCode.REFRESH_REPLAY_EXPIRED, now);
		}
		if (previous.consumedAt() != null) {
			end(session, now, "REVOKED", "SESSION_EXPIRED");
			throw new SessionInvalidException(ErrorCode.TOKEN_REUSED);
		}
		if (!previous.refreshExpiresAt().isAfter(now)) {
			end(session, now, "EXPIRED", "SESSION_EXPIRED");
			throw new SessionInvalidException(ErrorCode.SESSION_EXPIRED);
		}
		Instant expiry = "GUEST".equals(session.kind()) ? session.expiresAt() : tokens.memberExpiry(now);
		// 만료 직전 1초 미만은 유효한 JWT NumericDate를 발급할 수 없다.
		if (expiry.getEpochSecond() <= now.getEpochSecond()) throw new CustomException(ErrorCode.SESSION_EXPIRED);
		repository.consume(previous.id(), now);
		repository.extend(session.id(), expiry);
		Tokens issued = tokens.issue(session.id(), expiry, now);
		UUID credentialId = saveCredential(issued, previous.generation() + 1, now);
		save(session, scopeHash, "A03", key, requestHash, credentialId, issued, now.plusSeconds(30), now);
		return issued;
	}

	public void logout(String access, UUID key) {
		Instant now = now();
		UUID sessionId = tokens.verify(access);
		Session session = repository.lockSession(sessionId);
		if (session == null) throw new CustomException(ErrorCode.SESSION_EXPIRED);
		if (!"KAKAO".equals(session.kind())) throw new CustomException(ErrorCode.LOGIN_REQUIRED);
		Credential proof = repository.credentialByAccess(accessHash(access));
		if (proof == null || !proof.sessionId().equals(session.id())) throw new CustomException(ErrorCode.SESSION_EXPIRED);
		byte[] scopeHash = scope("SESSION:" + session.id());
		byte[] hash = crypto.hash("LOGOUT", access);
		Replay replay = repository.replay(scopeHash, "A04", key);
		if (replay != null) {
			match(replay, hash);
			if (replay.responseExpiresAt() == null || !replay.responseExpiresAt().isAfter(now)) {
				throw new CustomException(ErrorCode.SESSION_EXPIRED);
			}
			return;
		}
		authenticate(access, session, now);
		end(session, now, "REVOKED", "LOGOUT");
		save(session, scopeHash, "A04", key, hash, session.id(), null, now.plusSeconds(60), now);
	}

	public SessionView session(String access) {
		Session session = authenticated(access);
		return sessionView(session);
	}
	public void expire(UUID sessionId) {
		Session session = repository.lockSession(sessionId);
		Instant now = now();
		if (session != null && "ACTIVE".equals(session.status()) && !session.expiresAt().isAfter(now)) {
			end(session, now, "EXPIRED", "SESSION_EXPIRED");
		}
	}
	public OnboardingView onboarding(String access) {
		return onboardingView(authenticated(access));
	}
	public OnboardingView advance(String access, AdvanceRequest request, UUID key) {
		Session session = authenticated(access);
		Instant now = now();
		byte[] scopeHash = scope("SESSION:" + session.id());
		byte[] hash = requestHash(request);
		Replay replay = repository.replay(scopeHash, "A07", key);
		if (replay != null) {
			match(replay, hash);
			// 현재 동의/계정 상태로 다시 계산한다. 과거의 진행 가능 상태를 재생하지 않는다.
			return onboardingView(session);
		}
		if (request.expectedVersion() != session.version()) throw new CustomException(ErrorCode.VERSION_CONFLICT);
		if (request.step() != session.step() || request.step() == Step.COMPLETE) {
			throw new CustomException(ErrorCode.ONBOARDING_STEP_MISMATCH);
		}
		validateAdvance(session, request);
		var view = onboardingView(session);
		if (session.userId() != null && List.of(Step.PERMISSIONS, Step.SOS_GUIDE, Step.MESSAGE_TEST).contains(session.step())
			&& !view.requiredConsentCodes().isEmpty()) throw new CustomException(ErrorCode.CONSENT_REQUIRED);
		Step next = switch (session.step()) {
			case PROFILE -> {
				if (!view.requiredMissingFields().isEmpty()) throw new CustomException(ErrorCode.PROFILE_INCOMPLETE);
				User user = repository.user(session.userId(), false);
				yield "ACTIVE".equals(user.status()) ? (view.requiredConsentCodes().isEmpty() ? Step.PERMISSIONS : Step.CONSENTS) : Step.CONTACTS;
			}
			case CONTACTS -> Step.CONSENTS;
			case CONSENTS -> {
				if (!view.requiredConsentCodes().isEmpty()) throw new CustomException(ErrorCode.CONSENT_REQUIRED);
				yield Step.PERMISSIONS;
			}
			case PERMISSIONS -> Step.SOS_GUIDE;
			case SOS_GUIDE -> "GUEST".equals(session.kind()) ? Step.COMPLETE : Step.MESSAGE_TEST;
			case MESSAGE_TEST -> Step.COMPLETE;
			case COMPLETE -> throw new CustomException(ErrorCode.ONBOARDING_STEP_MISMATCH);
		};
		if (request.permissionReview() != null) {
			repository.observe(session, "PERMISSION", "MICROPHONE_PERMISSION_REVIEWED", observed(request.permissionReview().microphone()), now);
			if (session.userId() != null) repository.observe(session, "PERMISSION", "LOCATION_PERMISSION_REVIEWED",
				observed(request.permissionReview().location()), now);
		}
		if (session.step() == Step.SOS_GUIDE) repository.observe(session, "SOS", "SOS_GUIDE_VIEWED", null, now);
		repository.advance(session, next, now);
		Session updated = repository.session(session.id());
		save(updated, scopeHash, "A07", key, hash, session.id(), null, null, now);
		return onboardingView(updated);
	}
	private void validateAdvance(Session session, AdvanceRequest request) {
		boolean isPermissions = session.step() == Step.PERMISSIONS;
		boolean isTest = session.step() == Step.MESSAGE_TEST;
		if (isPermissions != (request.permissionReview() != null) || isTest != (request.testDecision() != null)) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
		if (isPermissions && (request.permissionReview().microphone() == null
			|| (session.userId() != null) != (request.permissionReview().location() != null))) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
	}
	private Boolean observed(Permission permission) {
		return permission == Permission.NOT_DETERMINED ? null : permission == Permission.GRANTED;
	}
	private Session authenticated(String access) {
		UUID id = tokens.verify(access);
		Session session = repository.lockSession(id);
		authenticate(access, session, now());
		return session;
	}
	private void authenticate(String access, Session session, Instant now) {
		ensureActive(session, now);
		Credential current = repository.currentCredential(session.id());
		if (current == null || !current.accessExpiresAt().isAfter(now)
			|| !crypto.isEqual(current.accessHash(), accessHash(access))) throw new CustomException(ErrorCode.SESSION_EXPIRED);
	}
	private void ensureActive(Session session, Instant now) {
		if (session == null || !"ACTIVE".equals(session.status())) throw new CustomException(ErrorCode.SESSION_EXPIRED);
		if (!session.expiresAt().isAfter(now)) {
			end(session, now, "EXPIRED", "SESSION_EXPIRED");
			throw new SessionInvalidException(ErrorCode.SESSION_EXPIRED);
		}
		User user = repository.user(session.userId(), false);
		if (session.userId() != null && (user == null || "DELETION_PENDING".equals(user.status()))) {
			throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
		}
	}
	private SessionView sessionView(Session session) {
		User user = repository.user(session.userId(), false);
		boolean isReconsentRequired = user != null && "ACTIVE".equals(user.status())
			&& !repository.missingConsents(user.id()).isEmpty();
		return new SessionView(session.id(), session.kind(), session.userId(), session.step(), session.expiresAt(), isReconsentRequired);
	}
	private OnboardingView onboardingView(Session session) {
		User user = repository.user(session.userId(), false);
		List<String> missing = new ArrayList<>();
		if (user != null) {
			if (user.nameCipher() == null) missing.add("name");
			if (user.phoneCipher() == null) missing.add("phone");
			if (user.confirmedAt() == null) missing.add("confirmedAt");
		}
		List<String> consents = repository.missingConsents(session.userId());
		boolean isAllowed = switch (session.step()) {
			case PROFILE -> missing.isEmpty();
			case CONSENTS -> consents.isEmpty();
			case COMPLETE -> false;
			case CONTACTS -> true;
			default -> session.userId() == null || consents.isEmpty();
		};
		return new OnboardingView(session.step(), missing, consents, isAllowed, session.version());
	}
	private ProfileView profile(User user) {
		if (user == null) return null;
		byte[] key = keys.read(user.keyRef());
		String gender = open(key, user.id(), "gender", user.genderCipher());
		String birthDate = open(key, user.id(), "birthDate", user.birthDateCipher());
		return new ProfileView(user.id(), open(key,user.id(),"name",user.nameCipher()), gender == null ? "UNKNOWN" : gender,
			birthDate == null ? null : LocalDate.parse(birthDate), open(key,user.id(),"phone",user.phoneCipher()),
			user.genderSource(), user.birthDateSource(), user.confirmedAt(), user.version());
	}
	private byte[] seal(byte[] key, UUID userId, String field, String value) {
		return crypto.seal(key, userId + ":" + field, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
	}
	private String open(byte[] key, UUID userId, String field, byte[] value) {
		return value == null ? null : new String(crypto.open(key, userId + ":" + field, value), StandardCharsets.UTF_8);
	}
	private UUID saveCredential(Tokens issued, int generation, Instant now) {
		return repository.insertCredential(issued, generation, accessHash(issued.accessToken()), refreshHash(issued.refreshToken()), now);
	}
	private void end(Session session, Instant now, String status, String reason) {
		repository.endSession(session, now, status, reason, crypto.hash("SERVER_EVENT", reason));
	}
	private void match(Replay replay, byte[] hash) {
		if (!crypto.isEqual(replay.requestHash(), hash)) throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
		if (!"DONE".equals(replay.status())) throw new CustomException(ErrorCode.REQUEST_IN_PROGRESS, 1);
	}
	private <T> T replay(Replay replay, Class<T> type, ErrorCode expired, Instant now) {
		if (replay.responseCipher() == null || replay.responseExpiresAt() == null || !replay.responseExpiresAt().isAfter(now)) {
			throw new CustomException(expired);
		}
		return mapper.readValue(crypto.openResponse(replay.id().toString(), replay.responseCipher()), type);
	}
	private void save(Session session, byte[] scope, String operation, UUID key, byte[] hash,
		UUID resource, Object response, Instant expiry, Instant now) {
		UUID id = UUID.randomUUID();
		byte[] cipher = response == null ? null : crypto.sealResponse(id.toString(), mapper.writeValueAsBytes(response));
		repository.saveReplay(id, session, scope, operation, key, hash, resource, cipher, expiry, now);
	}
}

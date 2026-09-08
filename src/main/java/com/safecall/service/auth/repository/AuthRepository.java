package com.safecall.service.auth.repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.repository.AuthRows.*;

@Repository
public class AuthRepository {
	private final JdbcTemplate jdbc;
	public AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	public static byte[] bin(UUID id) {
		return id == null ? null : ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}
	private static UUID uuid(ResultSet row, String column) throws SQLException {
		byte[] bytes = row.getBytes(column);
		if (bytes == null) return null;
		var buffer = ByteBuffer.wrap(bytes);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
	private static Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getObject(column, java.time.LocalDateTime.class);
		return value == null ? null : value.toInstant(java.time.ZoneOffset.UTC);
	}
	public static java.time.LocalDateTime time(Instant value) {
		return value == null ? null : java.time.LocalDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
	}
	private <T> T one(String sql, RowMapper<T> mapper, Object... args) {
		var result = jdbc.query(sql, mapper, args);
		return result.isEmpty() ? null : result.getFirst();
	}
	private static final RowMapper<Session> SESSION = (r, n) -> new Session(uuid(r,"id"), uuid(r,"installationId"),
		uuid(r,"userId"), r.getString("kind"), Step.valueOf(r.getString("onboardingStep")),
		r.getLong("version"), r.getString("status"), instant(r,"createdAt"), instant(r,"expiresAt"));
	private static final RowMapper<Credential> CREDENTIAL = (r, n) -> new Credential(uuid(r,"id"), uuid(r,"sessionId"),
		r.getInt("generation"), r.getBytes("accessHash"), r.getBytes("refreshHash"),
		instant(r,"accessExpiresAt"), instant(r,"refreshExpiresAt"), instant(r,"consumedAt"));
	private static final RowMapper<User> USER = (r,n) -> new User(uuid(r,"id"), r.getString("status"), r.getString("keyRef"),
		r.getBytes("nameCipher"), r.getBytes("genderCipher"), r.getBytes("birthDateCipher"), r.getBytes("phoneCipher"),
		r.getString("genderSource"), r.getString("birthDateSource"), instant(r,"profileConfirmedAt"), r.getLong("version"));

	public User userBySubject(byte[] hash) {
		return one("SELECT * FROM `appUser` WHERE `kakaoSubjectHash`=? FOR UPDATE", USER, hash);
	}
	public User user(UUID id, boolean isLock) {
		return id == null ? null : one("SELECT * FROM `appUser` WHERE `id`=?" + (isLock ? " FOR UPDATE" : ""), USER, bin(id));
	}
	public boolean isDeletionPending(byte[] hash) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT EXISTS(SELECT 1 FROM `deletionJob` WHERE `accountSubjectHash`=?
			AND `scope`='ACCOUNT' AND `pendingMarker`=1)
			""", Boolean.class, hash));
	}
	public void createUser(UUID id, byte[] subjectHash, byte[] subjectCipher, String keyRef,
		byte[] name, byte[] gender, byte[] birthDate, byte[] phone, byte[] phoneHash, Instant now) {
		jdbc.update("""
			INSERT INTO `appUser` (`id`,`kakaoSubjectHash`,`kakaoSubjectCipher`,`keyRef`,
			`nameCipher`,`genderCipher`,`birthDateCipher`,`phoneCipher`,`phoneHash`,
			`genderSource`,`birthDateSource`,`createdAt`,`updatedAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
			""", bin(id), subjectHash, subjectCipher, keyRef, name, gender, birthDate, phone, phoneHash,
			gender == null ? "UNKNOWN" : "KAKAO", birthDate == null ? "UNKNOWN" : "KAKAO", time(now), time(now));
		jdbc.update("INSERT INTO `userSetting` (`userId`,`updatedAt`) VALUES (?,?)", bin(id), time(now));
	}
	public UUID installation(byte[] hash, String appVersion, String osVersion, Instant now) {
		// 유일 키의 upsert가 설치 생성 경쟁과 이후 세션 교체를 직렬화한다.
		jdbc.update("""
			INSERT INTO `deviceInstallation` (`id`,`installationHash`,`keyRef`,`appVersion`,`osVersion`,`createdAt`,`lastSeenAt`)
			VALUES (?,?,'HMAC_V1',?,?,?,?) ON DUPLICATE KEY UPDATE `id`=`id`
			""", bin(UUID.randomUUID()), hash, appVersion, osVersion, time(now), time(now));
		return one("SELECT `id` FROM `deviceInstallation` WHERE `installationHash`=? FOR UPDATE",
			(r,n) -> uuid(r,"id"), hash);
	}
	public void updateInstallation(UUID id, String appVersion, String osVersion, Instant now) {
		jdbc.update("UPDATE `deviceInstallation` SET `appVersion`=?,`osVersion`=?,`lastSeenAt`=? WHERE `id`=?",
			appVersion, osVersion, time(now), bin(id));
	}
	public Session session(UUID id) {
		return one("SELECT * FROM `deviceSession` WHERE `id`=?", SESSION, bin(id));
	}
	public Session lockSession(UUID id) {
		Session snapshot = session(id);
		if (snapshot == null) return null;
		// 동일한 잠금 순서: 회원 → 설치 → 세션 → credential → 통화.
		user(snapshot.userId(), true);
		jdbc.queryForList("SELECT `id` FROM `deviceInstallation` WHERE `id`=? FOR UPDATE", bin(snapshot.installationId()));
		return one("SELECT * FROM `deviceSession` WHERE `id`=? FOR UPDATE", SESSION, bin(id));
	}
	public Session activeSession(UUID installationId) {
		return one("SELECT * FROM `deviceSession` WHERE `installationId`=? AND `status`='ACTIVE' FOR UPDATE",
			SESSION, bin(installationId));
	}
	public void createSession(UUID id, UUID installationId, UUID userId, Instant now, Instant expiry) {
		jdbc.update("""
			INSERT INTO `deviceSession` (`id`,`installationId`,`userId`,`kind`,`onboardingStep`,`createdAt`,`expiresAt`)
			VALUES (?,?,?,?,?,?,?)
			""", bin(id), bin(installationId), bin(userId), userId == null ? "GUEST" : "KAKAO",
			userId == null ? "PERMISSIONS" : "PROFILE", time(now), time(expiry));
	}
	public Credential credentialByRefresh(byte[] hash) {
		return one("SELECT * FROM `sessionCredential` WHERE `refreshHash`=?", CREDENTIAL, hash);
	}
	public Credential credentialByAccess(byte[] hash) {
		return one("SELECT * FROM `sessionCredential` WHERE `accessHash`=?", CREDENTIAL, hash);
	}
	public Credential credential(UUID id) {
		return one("SELECT * FROM `sessionCredential` WHERE `id`=? FOR UPDATE", CREDENTIAL, bin(id));
	}
	public Credential currentCredential(UUID sessionId) {
		return one("SELECT * FROM `sessionCredential` WHERE `sessionId`=? AND `consumedAt` IS NULL FOR UPDATE",
			CREDENTIAL, bin(sessionId));
	}
	public UUID insertCredential(Tokens tokens, int generation, byte[] accessHash, byte[] refreshHash, Instant now) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO `sessionCredential` (`id`,`sessionId`,`generation`,`accessHash`,`refreshHash`,
			`accessExpiresAt`,`refreshExpiresAt`,`createdAt`) VALUES (?,?,?,?,?,?,?,?)
			""", bin(id), bin(tokens.sessionId()), generation, accessHash, refreshHash,
			time(tokens.accessExpiresAt()), time(tokens.refreshExpiresAt()), time(now));
		return id;
	}
	public void consume(UUID credentialId, Instant now) {
		jdbc.update("UPDATE `sessionCredential` SET `consumedAt`=? WHERE `id`=? AND `consumedAt` IS NULL", time(now), bin(credentialId));
	}
	public void extend(UUID id, Instant expiry) {
		jdbc.update("UPDATE `deviceSession` SET `expiresAt`=? WHERE `id`=?", time(expiry), bin(id));
	}
	public void endSession(Session session, Instant now, String status, String reason, byte[] eventHash) {
		jdbc.update("UPDATE `deviceSession` SET `status`=?,`revokedAt`=? WHERE `id`=?",
			status, "REVOKED".equals(status) ? time(now) : null, bin(session.id()));
		jdbc.update("UPDATE `sessionCredential` SET `consumedAt`=COALESCE(`consumedAt`,?) WHERE `sessionId`=?",
			time(now), bin(session.id()));
		var calls = jdbc.query("SELECT `id` FROM `callSession` WHERE `sessionId`=? AND `activeMarker`=1 FOR UPDATE",
			(r,n) -> uuid(r,"id"), bin(session.id()));
		for (UUID callId : calls) {
			jdbc.update("""
				UPDATE `callSession` SET `state`='ENDED',`endReason`=?,`endedAt`=?,`version`=`version`+1 WHERE `id`=?
				""", reason, time(now), bin(callId));
			jdbc.update("""
				INSERT INTO `callEvent` (`id`,`callId`,`sequence`,`eventKey`,`requestHash`,`eventType`,`stateAfter`,`occurredAt`,`recordedAt`)
				SELECT ?,?,COALESCE(MAX(`sequence`),0)+1,?,?,'ENDED','ENDED',?,? FROM `callEvent` WHERE `callId`=?
				""", bin(UUID.randomUUID()), bin(callId), bin(UUID.randomUUID()), eventHash, time(now), time(now), bin(callId));
			jdbc.update("UPDATE `connectionGrant` SET `status`='INVALIDATED',`tokenCipher`=NULL WHERE `callId`=?", bin(callId));
		}
		jdbc.update("UPDATE `apiIdempotency` SET `responseCipher`=NULL WHERE `ownerSessionId`=?", bin(session.id()));
	}
	public Replay replay(byte[] scopeHash, String operation, UUID key) {
		return one("""
			SELECT * FROM `apiIdempotency` WHERE `scopeHash`=? AND `operation`=? AND `requestKey`=? FOR UPDATE
			""", (r,n) -> new Replay(uuid(r,"id"), uuid(r,"ownerSessionId"), uuid(r,"resourceId"),
			r.getBytes("requestHash"), r.getString("status"), r.getBytes("responseCipher"), instant(r,"responseExpiresAt")),
			scopeHash, operation, bin(key));
	}
	public void saveReplay(UUID id, Session session, byte[] scope, String operation, UUID key, byte[] requestHash,
		UUID resourceId, byte[] response, Instant responseExpiry, Instant now) {
		jdbc.update("""
			INSERT INTO `apiIdempotency` (`id`,`ownerUserId`,`ownerSessionId`,`scopeHash`,`operation`,`requestKey`,
			`requestHash`,`status`,`resourceId`,`responseCipher`,`responseExpiresAt`,`createdAt`,`expiresAt`)
			VALUES (?,?,?,?,?,?,?,'DONE',?,?,?,?,?)
			""", bin(id), bin(session.userId()), bin(session.id()), scope, operation, bin(key), requestHash,
			bin(resourceId), response, time(responseExpiry), time(now), time(now.plusSeconds(86400)));
	}
	public List<String> missingConsents(UUID userId) {
		if (userId == null) return List.of();
		return List.of("PRIVACY_PROCESSING", "AI_CALL").stream().filter(code -> !Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT EXISTS(SELECT 1 FROM `serviceDocument` d
			WHERE d.`code`=? AND d.`isCurrent`=1 AND d.`isRequired`=1 AND d.`isConsent`=1
			AND EXISTS(SELECT 1 FROM `consentEvent` e WHERE e.`id`=(
				SELECT e2.`id` FROM `consentEvent` e2 WHERE e2.`userId`=? AND e2.`documentCode`=d.`code`
				ORDER BY e2.`recordedAt` DESC,e2.`id` DESC LIMIT 1)
			AND e.`action`='GRANTED' AND e.`documentVersion`=d.`version`))
			""", Boolean.class, code, bin(userId)))).toList();
	}
	public void advance(Session session, Step next, Instant now) {
		jdbc.update("UPDATE `deviceSession` SET `onboardingStep`=?,`version`=`version`+1 WHERE `id`=?", next.name(), bin(session.id()));
		if (next == Step.COMPLETE && session.userId() != null) {
			jdbc.update("UPDATE `appUser` SET `status`='ACTIVE',`updatedAt`=?,`version`=`version`+1 WHERE `id`=?",
				time(now), bin(session.userId()));
		}
	}
	public void observe(Session session, String category, String code, Boolean isSuccess, Instant now) {
		jdbc.update("""
			INSERT INTO `operationEvent` (`id`,`userId`,`sessionId`,`eventKey`,`category`,`code`,`isSuccess`,`occurredAt`,`recordedAt`)
			VALUES (?,?,?,?,?,?,?,?,?)
			""", bin(UUID.randomUUID()), bin(session.userId()), bin(session.id()), bin(UUID.randomUUID()),
			category, code, isSuccess, time(now), time(now));
	}
	public int countAttempt(byte[] hash, Instant window, Instant expiry) {
		jdbc.update("""
			INSERT INTO `rateBucket` (`scopeKind`,`scopeHash`,`operation`,`windowStart`,`windowSeconds`,`usedCount`,`expiresAt`)
			VALUES ('IP',?,'AUTH',?,60,1,?) ON DUPLICATE KEY UPDATE `usedCount`=`usedCount`+1
			""", hash, time(window), time(expiry));
		return jdbc.queryForObject("""
			SELECT `usedCount` FROM `rateBucket` WHERE `scopeKind`='IP' AND `scopeHash`=?
			AND `operation`='AUTH' AND `windowStart`=? AND `windowSeconds`=60
			""", Integer.class, hash, time(window));
	}
	public void purgeResponses(Instant now) {
		jdbc.update("UPDATE `apiIdempotency` SET `responseCipher`=NULL WHERE `responseExpiresAt`<=? AND `responseCipher` IS NOT NULL", time(now));
		jdbc.update("DELETE FROM `apiIdempotency` WHERE `expiresAt`<=?", time(now));
		jdbc.update("DELETE FROM `rateBucket` WHERE `expiresAt`<=?", time(now));
		jdbc.update("DELETE FROM `sessionCredential` WHERE `refreshExpiresAt`<=? AND `consumedAt` IS NOT NULL", time(now));
	}
	public List<UUID> expiredSessions(Instant now) {
		return jdbc.query("SELECT `id` FROM `deviceSession` WHERE `status`='ACTIVE' AND `expiresAt`<=? ORDER BY `expiresAt` LIMIT 100",
			(r,n) -> uuid(r,"id"), time(now));
	}
}

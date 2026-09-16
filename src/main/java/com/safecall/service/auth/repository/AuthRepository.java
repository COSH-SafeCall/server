package com.safecall.service.auth.repository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.repository.AuthRows.*;
import com.safecall.service.common.crypto.TransientKeys;

@Repository
public class AuthRepository {
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final TransientKeys keys;
	public AuthRepository(JdbcTemplate jdbc,Clock clock,TransientKeys keys) { this.jdbc = jdbc; this.clock=clock;this.keys=keys; }
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
	private static final RowMapper<Session> SESSION = (r,n) -> new Session(uuid(r,"id"), r.getBytes("sessionHash"),
		r.getBytes("csrfHash"), uuid(r,"userId"), r.getString("kind"), r.getString("status"), instant(r,"createdAt"), instant(r,"expiresAt"));
	private static final RowMapper<User> USER = (r,n) -> new User(uuid(r,"id"), r.getString("status"), r.getString("keyRef"),
		r.getBytes("nameCipher"), r.getBytes("genderCipher"), r.getBytes("birthDateCipher"), r.getBytes("phoneCipher"),
		r.getString("genderSource"), r.getString("birthDateSource"), instant(r,"profileConfirmedAt"), r.getLong("version"));

	public User user(UUID id, boolean isLock) {
		return id == null ? null : one("SELECT * FROM `appUser` WHERE `id`=?" + (isLock ? " FOR UPDATE" : ""), USER, bin(id));
	}
	public void createUser(UUID id, String keyRef, Instant now) {
		jdbc.update("""
			INSERT INTO `appUser` (`id`,`keyRef`,`createdAt`,`updatedAt`) VALUES (?,?,?,?)
			""", bin(id), keyRef, time(now), time(now));
		jdbc.update("INSERT INTO `userSetting` (`userId`,`updatedAt`) VALUES (?,?)", bin(id), time(now));
	}

	public Session session(UUID id) { return one("SELECT * FROM `webSession` WHERE `id`=?",SESSION,bin(id)); }
	public Session byCookie(byte[] hash) { return one("SELECT * FROM `webSession` WHERE `sessionHash`=?",SESSION,hash); }
	public Session lockSession(UUID id) {
		Session snapshot=session(id); if(snapshot==null)return null;
		user(snapshot.userId(),true);
		return one("SELECT * FROM `webSession` WHERE `id`=? FOR UPDATE",SESSION,bin(id));
	}
	public void createSession(UUID id,UUID user,String kind,byte[] hash,byte[] csrf,Instant now,Instant expiry) {
		jdbc.update("INSERT INTO `webSession` (`id`,`sessionHash`,`csrfHash`,`userId`,`kind`,`createdAt`,`expiresAt`) VALUES (?,?,?,?,?,?,?)",
			bin(id),hash,csrf,bin(user),kind,time(now),time(expiry));
	}
	public void endSession(Session session, Instant now, String status, String reason, byte[] eventHash) {
		jdbc.update("UPDATE `webSession` SET `status`=?,`revokedAt`=? WHERE `id`=?",
			status, "REVOKED".equals(status) ? time(now) : null, bin(session.id()));
		endCalls(session, now, reason, eventHash);
		jdbc.update("UPDATE `apiIdempotency` SET `responseCipher`=NULL WHERE `ownerSessionId`=?", bin(session.id()));
	}
	public void endCalls(Session session, Instant now, String reason, byte[] eventHash) {
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
			jdbc.queryForList("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=? AND `keyRef` IS NOT NULL",String.class,bin(callId)).forEach(keys::discardAfterCommit);
			jdbc.update("UPDATE `connectionGrant` SET `status`='INVALIDATED',`tokenCipher`=NULL,`keyRef`=NULL WHERE `callId`=? AND `status` IN ('PENDING','ISSUING','READY')", bin(callId));
		}
	}
	public Replay replay(byte[] scopeHash, String operation, UUID key) {
		jdbc.update("DELETE FROM `apiIdempotency` WHERE `scopeHash`=? AND `operation`=? AND `requestKey`=? AND `expiresAt`<=?",scopeHash,operation,bin(key),time(clock.instant()));
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
	public void observe(Session session, String category, String code, Boolean isSuccess, Instant now) {
		jdbc.update("""
			INSERT INTO `operationEvent` (`id`,`sessionId`,`eventKey`,`category`,`code`,`isSuccess`,`occurredAt`,`recordedAt`)
			VALUES (?,?,?,?,?,?,?,?)
			""", bin(UUID.randomUUID()), bin(session.id()), bin(UUID.randomUUID()),
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
	}
	public List<UUID> expiredSessions(Instant now) {
		return jdbc.query("SELECT `id` FROM `webSession` WHERE `status`='ACTIVE' AND `expiresAt`<=? ORDER BY `expiresAt` LIMIT 100",
			(r,n) -> uuid(r,"id"), time(now));
	}
}

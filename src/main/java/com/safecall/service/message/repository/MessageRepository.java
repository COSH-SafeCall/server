package com.safecall.service.message.repository;

import static com.safecall.service.auth.repository.AuthRepository.bin;
import static com.safecall.service.auth.repository.AuthRepository.time;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.safecall.service.auth.api.AuthDtos.Step;
import com.safecall.service.user.repository.UserRepository.Contact;

@Repository
public class MessageRepository {
	private final JdbcTemplate jdbc;
	public MessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public record Material(UUID userId, String keyRef, byte[] name, byte[] phone, boolean isConfirmed,
		Step step, Instant sessionExpiresAt, boolean isPrivacyGranted, boolean isLocationGranted,
		boolean isCleanupPending, boolean isCallOpen, Contact contact) {
		@Override public String toString() { return "Material[redacted]"; }
	}

	public List<Material> snapshot(UUID sessionId, Instant now) {
		// READ_COMMITTED의 단일 SELECT로 문서 발행·동의·프로필·연락망·통화를 같은 스냅샷에서 읽는다.
		return jdbc.query("""
			SELECT u.`id` AS `userId`, u.`keyRef`, u.`nameCipher`, u.`phoneCipher`,
				u.`profileConfirmedAt` IS NOT NULL AS `isConfirmed`, s.`onboardingStep`, s.`expiresAt`,
				EXISTS(SELECT 1 FROM `serviceDocument` d WHERE d.`code`='PRIVACY_PROCESSING'
					AND d.`isCurrent`=1 AND d.`isConsent`=1 AND d.`publishedAt`<=?
					AND (SELECT e.`action` FROM `consentEvent` e WHERE e.`userId`=u.`id` AND e.`documentCode`=d.`code`
						ORDER BY e.`recordedAt` DESC,e.`id` DESC LIMIT 1)='GRANTED'
					AND d.`version`=(SELECT e.`documentVersion` FROM `consentEvent` e WHERE e.`userId`=u.`id` AND e.`documentCode`=d.`code`
						ORDER BY e.`recordedAt` DESC,e.`id` DESC LIMIT 1)) AS `isPrivacyGranted`,
				EXISTS(SELECT 1 FROM `serviceDocument` d WHERE d.`code`='LOCATION_PROCESSING'
					AND d.`isCurrent`=1 AND d.`isConsent`=1 AND d.`publishedAt`<=?
					AND (SELECT e.`action` FROM `consentEvent` e WHERE e.`userId`=u.`id` AND e.`documentCode`=d.`code`
						ORDER BY e.`recordedAt` DESC,e.`id` DESC LIMIT 1)='GRANTED'
					AND d.`version`=(SELECT e.`documentVersion` FROM `consentEvent` e WHERE e.`userId`=u.`id` AND e.`documentCode`=d.`code`
						ORDER BY e.`recordedAt` DESC,e.`id` DESC LIMIT 1)) AS `isLocationGranted`,
				(u.`status`='DELETION_PENDING' OR EXISTS(SELECT 1 FROM `deletionJob` j
					WHERE j.`userId`=u.`id` AND j.`pendingMarker`=1)) AS `isCleanupPending`,
				EXISTS(SELECT 1 FROM `callSession` c WHERE c.`sessionId`=s.`id` AND c.`activeMarker`=1) AS `isCallOpen`,
				r.`id` AS `contactId`, r.`slot`, r.`nameCipher` AS `contactName`,
				r.`relationshipCipher` AS `contactRelationship`, r.`phoneCipher` AS `contactPhone`, r.`version`
			FROM `webSession` s JOIN `appUser` u ON u.`id`=s.`userId`
			LEFT JOIN `emergencyContact` r ON r.`userId`=u.`id`
			WHERE s.`id`=? AND s.`kind`='KAKAO' AND s.`status`='ACTIVE' AND s.`expiresAt`>?
			ORDER BY r.`slot`
			""", (r,n) -> {
				UUID contactId = uuid(r, "contactId");
				Contact contact = contactId == null ? null : new Contact(contactId, r.getInt("slot"),
					r.getBytes("contactName"), r.getBytes("contactRelationship"), r.getBytes("contactPhone"), null, r.getLong("version"));
				return new Material(uuid(r,"userId"), r.getString("keyRef"), r.getBytes("nameCipher"), r.getBytes("phoneCipher"),
					r.getBoolean("isConfirmed"), Step.valueOf(r.getString("onboardingStep")),
					r.getObject("expiresAt", java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC),
					r.getBoolean("isPrivacyGranted"), r.getBoolean("isLocationGranted"), r.getBoolean("isCleanupPending"),
					r.getBoolean("isCallOpen"), contact);
			}, time(now), time(now), bin(sessionId), time(now));
	}
	private static UUID uuid(ResultSet row, String column) throws SQLException {
		byte[] bytes = row.getBytes(column);
		if (bytes == null) return null;
		var buffer = ByteBuffer.wrap(bytes);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}

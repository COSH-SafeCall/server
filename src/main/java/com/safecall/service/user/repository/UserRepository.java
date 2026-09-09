package com.safecall.service.user.repository;
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
import com.safecall.service.user.api.UserDtos.*;

@Repository
public class UserRepository {
	private final JdbcTemplate jdbc;
	public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	public record Contact(UUID id, int slot, byte[] name, byte[] relationship, byte[] phone, byte[] phoneHash, long version) {}
	public record Document(String code, int version, String title, String body, boolean isConsent, boolean isRequired, Instant publishedAt) {}
	public record Event(String action, int version, Instant recordedAt) {}
	private static UUID id(ResultSet r, String name) throws SQLException {
		var b = ByteBuffer.wrap(r.getBytes(name)); return new UUID(b.getLong(), b.getLong());
	}
	private static Instant instant(ResultSet r, String name) throws SQLException {
		var date = r.getObject(name, java.time.LocalDateTime.class);
		return date == null ? null : date.toInstant(java.time.ZoneOffset.UTC);
	}
	public List<Contact> contacts(UUID user) {
		return jdbc.query("SELECT * FROM `emergencyContact` WHERE `userId`=? ORDER BY `slot`", (r,n) ->
			new Contact(id(r,"id"), r.getInt("slot"), r.getBytes("nameCipher"), r.getBytes("relationshipCipher"),
				r.getBytes("phoneCipher"), r.getBytes("phoneHash"), r.getLong("version")), bin(user));
	}
	public byte[] ownPhoneHash(UUID user) {
		return jdbc.queryForObject("SELECT `phoneHash` FROM `appUser` WHERE `id`=?", byte[].class, bin(user));
	}
	public void updateProfile(UUID user, byte[] name, byte[] gender, byte[] birth, byte[] phone, byte[] phoneHash,
		String genderSource, String birthSource, Instant now) {
		jdbc.update("""
			UPDATE `appUser` SET `nameCipher`=?,`genderCipher`=?,`birthDateCipher`=?,`phoneCipher`=?,`phoneHash`=?,
			`genderSource`=?,`birthDateSource`=?,`profileConfirmedAt`=?,`updatedAt`=?,`version`=`version`+1 WHERE `id`=?
			""", name, gender, birth, phone, phoneHash, genderSource, birthSource, time(now), time(now), bin(user));
	}
	public void createContact(UUID user, UUID contact, int slot, byte[] name, byte[] relationship, byte[] phone, byte[] hash, Instant now) {
		jdbc.update("""
			INSERT INTO `emergencyContact` (`id`,`userId`,`slot`,`nameCipher`,`relationshipCipher`,`phoneCipher`,`phoneHash`,`createdAt`,`updatedAt`)
			VALUES (?,?,?,?,?,?,?,?,?)
			""", bin(contact), bin(user), slot, name, relationship, phone, hash, time(now), time(now));
	}
	public void updateContact(UUID user, UUID contact, byte[] name, byte[] relationship, byte[] phone, byte[] hash, Instant now) {
		jdbc.update("""
			UPDATE `emergencyContact` SET `nameCipher`=?,`relationshipCipher`=?,`phoneCipher`=?,`phoneHash`=?,
			`updatedAt`=?,`version`=`version`+1 WHERE `id`=? AND `userId`=?
			""", name, relationship, phone, hash, time(now), bin(contact), bin(user));
	}
	public void deleteContact(UUID user, UUID contact) {
		jdbc.update("DELETE FROM `emergencyContact` WHERE `id`=? AND `userId`=?", bin(contact), bin(user));
	}
	public SettingView settings(UUID user) {
		return jdbc.queryForObject("SELECT * FROM `userSetting` WHERE `userId`=?",
			(r,n) -> new SettingView(AlertMode.valueOf(r.getString("incomingAlertMode")), r.getLong("version")), bin(user));
	}
	public void updateSettings(UUID user, AlertMode mode, Instant now) {
		jdbc.update("UPDATE `userSetting` SET `incomingAlertMode`=?,`updatedAt`=?,`version`=`version`+1 WHERE `userId`=?",
			mode.name(), time(now), bin(user));
	}
	public List<Document> documents(boolean isLock) {
		return jdbc.query("SELECT * FROM `serviceDocument` WHERE `isCurrent`=1 ORDER BY `code`" + (isLock ? " FOR SHARE" : ""),
			(r,n) -> new Document(r.getString("code"),r.getInt("version"),r.getString("title"),r.getString("body"),
				r.getBoolean("isConsent"),r.getBoolean("isRequired"),instant(r,"publishedAt")));
	}
	public Event latest(UUID user, String code) {
		var rows = jdbc.query("""
			SELECT * FROM `consentEvent` WHERE `userId`=? AND `documentCode`=? ORDER BY `recordedAt` DESC,`id` DESC LIMIT 1
			""", (r,n) -> new Event(r.getString("action"),r.getInt("documentVersion"),instant(r,"recordedAt")),bin(user),code);
		return rows.isEmpty() ? null : rows.getFirst();
	}
	public void decision(UUID user, String code, int version, String action, Instant now) {
		Event latest = latest(user, code);
		Instant timestamp = latest != null && !now.isAfter(latest.recordedAt()) ? latest.recordedAt().plusNanos(1000) : now;
		jdbc.update("INSERT INTO `consentEvent` (`id`,`userId`,`documentCode`,`documentVersion`,`action`,`recordedAt`) VALUES (?,?,?,?,?,?)",
			bin(UUID.randomUUID()),bin(user),code,version,action,time(timestamp));
	}
	public boolean wasWithdrawn(UUID user, String code) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM `consentEvent` WHERE `userId`=? AND `documentCode`=? AND `action`='WITHDRAWN')",Boolean.class,bin(user),code));
	}
	public boolean pending(UUID user, String scope) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM `deletionJob` WHERE `userId`=? AND `scope`=? AND `pendingMarker`=1)",Boolean.class,bin(user),scope));
	}
	public List<UUID> sessions(UUID user) {
		return jdbc.query("SELECT `id` FROM `deviceSession` WHERE `userId`=? ORDER BY `installationId`,`id`",(r,n) -> id(r,"id"),bin(user));
	}
	public void deletion(UUID user, DeletionReceipt receipt, byte[] receiptHash, Instant now) {
		jdbc.update("""
			INSERT INTO `deletionJob` (`id`,`userId`,`scope`,`accountSubjectHash`,`receiptHash`,`requestedAt`,`cutoffAt`,`dueAt`,`receiptExpiresAt`)
			SELECT ?,?, ?,CASE WHEN ?='ACCOUNT' THEN `kakaoSubjectHash` ELSE NULL END,?,?,?,?,? FROM `appUser` WHERE `id`=?
			""",bin(receipt.id()),bin(user),receipt.scope(),receipt.scope(),receiptHash,time(now),time(now),time(receipt.dueAt()),time(receipt.receiptExpiresAt()),bin(user));
		if (receipt.scope().equals("ACCOUNT")) jdbc.update("UPDATE `appUser` SET `status`='DELETION_PENDING',`version`=`version`+1,`updatedAt`=? WHERE `id`=?",time(now),bin(user));
	}
}

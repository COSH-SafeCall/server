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
import com.safecall.service.auth.api.AuthDtos.Permission;
import com.safecall.service.user.api.UserDtos.*;

@Repository
public class UserRepository {
	private final JdbcTemplate jdbc;
	public UserRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
	public record Contact(UUID id, int slot, byte[] name, byte[] relationship, byte[] phone, byte[] phoneHash, long version) {}
	public record PermissionState(Permission microphone,Permission location,Instant updatedAt) {}
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
		String genderSource, String birthSource, Instant now,long expectedVersion) {
		jdbc.update("""
			UPDATE `appUser` SET `nameCipher`=?,`genderCipher`=?,`birthDateCipher`=?,`phoneCipher`=?,`phoneHash`=?,
			`genderSource`=?,`birthDateSource`=?,`profileConfirmedAt`=?,`updatedAt`=?,`version`=`version`+1 WHERE `id`=? AND `version`=?
			""", name, gender, birth, phone, phoneHash, genderSource, birthSource, time(now), time(now), bin(user),expectedVersion);
	}
	public void createContact(UUID user, UUID contact, int slot, byte[] name, byte[] relationship, byte[] phone, byte[] hash, Instant now) {
		jdbc.update("""
			INSERT INTO `emergencyContact` (`id`,`userId`,`slot`,`nameCipher`,`relationshipCipher`,`phoneCipher`,`phoneHash`,`createdAt`,`updatedAt`)
			VALUES (?,?,?,?,?,?,?,?,?)
			""", bin(contact), bin(user), slot, name, relationship, phone, hash, time(now), time(now));
	}
	public void updateContact(UUID user, UUID contact, byte[] name, byte[] relationship, byte[] phone, byte[] hash, Instant now,long expectedVersion) {
		jdbc.update("""
			UPDATE `emergencyContact` SET `nameCipher`=?,`relationshipCipher`=?,`phoneCipher`=?,`phoneHash`=?,
			`updatedAt`=?,`version`=`version`+1 WHERE `id`=? AND `userId`=? AND `version`=?
			""", name, relationship, phone, hash, time(now), bin(contact), bin(user),expectedVersion);
	}
	public void deleteContact(UUID user, UUID contact) {
		jdbc.update("DELETE FROM `emergencyContact` WHERE `id`=? AND `userId`=?", bin(contact), bin(user));
	}
	public SettingView settings(UUID user) {
		return jdbc.queryForObject("SELECT * FROM `userSetting` WHERE `userId`=?",
			(r,n) -> new SettingView(AlertMode.valueOf(r.getString("incomingAlertMode")), r.getLong("version")), bin(user));
	}
	public void updateSettings(UUID user, AlertMode mode, Instant now,long expectedVersion) {
		jdbc.update("UPDATE `userSetting` SET `incomingAlertMode`=?,`updatedAt`=?,`version`=`version`+1 WHERE `userId`=? AND `version`=?",
			mode.name(), time(now), bin(user),expectedVersion);
	}
	public PermissionState permissions(UUID user) {
		return jdbc.queryForObject("SELECT `microphonePermission`,`locationPermission`,`updatedAt` FROM `userSetting` WHERE `userId`=?",
			(r,n) -> new PermissionState(Permission.valueOf(r.getString("microphonePermission")),Permission.valueOf(r.getString("locationPermission")),instant(r,"updatedAt")),bin(user));
	}
	public void updatePermissions(UUID user,Permission microphone,Permission location,Instant now) {
		jdbc.update("""
			UPDATE `userSetting` SET `microphonePermission`=COALESCE(?,`microphonePermission`),
			`locationPermission`=COALESCE(?,`locationPermission`),`updatedAt`=?,`version`=`version`+1 WHERE `userId`=?
			""",microphone==null?null:microphone.name(),location==null?null:location.name(),time(now),bin(user));
	}
	public boolean pending(UUID user, String scope) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM `deletionJob` WHERE `userId`=? AND `scope`=? AND `pendingMarker`=1)",Boolean.class,bin(user),scope));
	}
	public List<UUID> sessions(UUID user) {
		return jdbc.query("SELECT `id` FROM `webSession` WHERE `userId`=? ORDER BY `id`",(r,n) -> id(r,"id"),bin(user));
	}
	public DeletionView deletionView(UUID id) {
		return jdbc.queryForObject("SELECT * FROM `deletionJob` WHERE `id`=?",(r,n)->new DeletionView(id,r.getString("scope"),r.getString("status"),instant(r,"requestedAt"),instant(r,"dueAt"),instant(r,"completedAt"),r.getString("errorCode")),bin(id));
	}
	public void deletion(UUID user, DeletionReceipt receipt, byte[] receiptHash, Instant now) {
		jdbc.update("""
			INSERT INTO `deletionJob` (`id`,`userId`,`scope`,`receiptHash`,`requestedAt`,`cutoffAt`,`dueAt`,`receiptExpiresAt`)
			VALUES (?,?,?,?,?,?,?,?)
			""",bin(receipt.id()),bin(user),receipt.scope(),receiptHash,time(now),time(now),time(receipt.dueAt()),time(receipt.receiptExpiresAt()));
		if (receipt.scope().equals("ACCOUNT")) {
			jdbc.update("UPDATE `appUser` SET `status`='DELETION_PENDING',`version`=`version`+1,`updatedAt`=? WHERE `id`=?",time(now),bin(user));
		}
	}
}

package com.safecall.service.history.repository;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.safecall.service.history.api.HistoryDtos.UsageHistoryItem;
import com.safecall.service.history.service.HistoryCursor.Position;
import com.safecall.service.user.api.UserDtos.DeletionView;

@Repository
public class HistoryRepository {
	private final JdbcTemplate jdbc;
	public HistoryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
	public record Job(UUID id, UUID userId, DeletionView view, byte[] receiptHash, Instant receiptExpiresAt) {
		@Override public String toString() { return "Job[redacted]"; }
	}
	public List<UsageHistoryItem> history(UUID user, int limit, Position cursor) {
		var args=new ArrayList<Object>(); args.add(bin(user));
		String boundary="";
		if (cursor!=null) {
			boundary=" AND (c.`createdAt`<? OR (c.`createdAt`=? AND c.`id`<?))";
			args.add(time(cursor.createdAt())); args.add(time(cursor.createdAt())); args.add(bin(cursor.id()));
		}
		args.add(limit);
		return jdbc.query("""
			SELECT c.`id`,c.`state`,c.`startMode`,c.`scenarioCode`,c.`counterpartCode`,p.`displayName`,
				c.`createdAt`,c.`answeredAt`,c.`endedAt`,c.`endReason`
			FROM `callSession` c JOIN `webSession` s ON s.`id`=c.`sessionId`
			JOIN `counterpart` p ON p.`code`=c.`counterpartCode`
			WHERE s.`userId`=? AND s.`kind`='KAKAO' AND c.`state` IN ('ENDED','FAILED')
			"""+boundary+" ORDER BY c.`createdAt` DESC,c.`id` DESC LIMIT ?",
			(r,n)->new UsageHistoryItem(uuid(r,"id"),r.getString("state"),r.getString("startMode"),r.getString("scenarioCode"),
				r.getString("counterpartCode"),r.getString("displayName"),instant(r,"createdAt"),instant(r,"answeredAt"),instant(r,"endedAt"),r.getString("endReason")),args.toArray());
	}
	public Job job(UUID id) {
		var rows=jdbc.query("SELECT * FROM `deletionJob` WHERE `id`=?",(r,n)->new Job(id,uuid(r,"userId"),
			new DeletionView(id,r.getString("scope"),r.getString("status"),instant(r,"requestedAt"),instant(r,"dueAt"),instant(r,"completedAt"),r.getString("errorCode")),
			r.getBytes("receiptHash"),instant(r,"receiptExpiresAt")),bin(id));
		return rows.isEmpty()?null:rows.getFirst();
	}
	public UUID pending(UUID user, String scope) {
		var ids=jdbc.query("SELECT `id` FROM `deletionJob` WHERE `userId`=? AND `scope`=? AND `pendingMarker`=1 FOR UPDATE",
			(r,n)->uuid(r,"id"),bin(user),scope);
		return ids.isEmpty()?null:ids.getFirst();
	}
	public void receipt(UUID id, byte[] hash, Instant expiry) {
		jdbc.update("UPDATE `deletionJob` SET `receiptHash`=?,`receiptExpiresAt`=? WHERE `id`=?",hash,time(expiry),bin(id));
	}
	public record ReceiptReplay(UUID id,byte[] cipher) {}
	public ReceiptReplay receiptReplay(UUID job,Instant now) {
		var rows=jdbc.query("SELECT `id`,`responseCipher` FROM `apiIdempotency` WHERE `resourceId`=? AND `responseExpiresAt`>? AND `responseCipher` IS NOT NULL ORDER BY `responseExpiresAt` DESC LIMIT 1",
			(r,n)->new ReceiptReplay(uuid(r,"id"),r.getBytes("responseCipher")),bin(job),time(now));
		return rows.isEmpty()?null:rows.getFirst();
	}
	public boolean hasOpenCall(UUID user) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM `callSession` c JOIN `webSession` s ON s.`id`=c.`sessionId` WHERE s.`userId`=? AND c.`activeMarker`=1)",Boolean.class,bin(user)));
	}
	public static UUID uuid(ResultSet r,String column) throws SQLException {
		byte[] bytes=r.getBytes(column);if(bytes==null)return null;var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());
	}
	public static Instant instant(ResultSet r,String column) throws SQLException {
		var value=r.getObject(column,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);
	}
}

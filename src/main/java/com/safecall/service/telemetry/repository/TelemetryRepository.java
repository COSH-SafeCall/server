package com.safecall.service.telemetry.repository;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.safecall.service.telemetry.api.TelemetryDtos.TelemetryEvent;

@Repository
public class TelemetryRepository {
	private final JdbcTemplate jdbc;
	public TelemetryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
	public TelemetryEvent find(UUID session,UUID event) {
		var rows=jdbc.query("SELECT * FROM `operationEvent` WHERE `sessionId`=? AND `eventKey`=?",(r,n)->
			new TelemetryEvent(event,uuid(r.getBytes("callId")),r.getString("category"),r.getString("code"),
				r.getObject("isSuccess")==null?null:r.getBoolean("isSuccess"),(Integer)r.getObject("latencyMs"),
				r.getString("networkType"),r.getObject("occurredAt",LocalDateTime.class).toInstant(ZoneOffset.UTC)),bin(session),bin(event));
		return rows.isEmpty()?null:rows.getFirst();
	}
	public boolean ownsCall(UUID session,UUID call) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM `callSession` WHERE `id`=? AND `sessionId`=?",Integer.class,bin(call),bin(session))==1;
	}
	public int charge(byte[] scope,Instant window,int count) {
		jdbc.update("""
			INSERT INTO `rateBucket` (`scopeKind`,`scopeHash`,`operation`,`windowStart`,`windowSeconds`,`usedCount`,`expiresAt`)
			VALUES ('SESSION',?,'TELEMETRY',?,60,?,?) ON DUPLICATE KEY UPDATE `usedCount`=`usedCount`+?
			""",scope,time(window),count,time(window.plusSeconds(60)),count);
		return jdbc.queryForObject("SELECT `usedCount` FROM `rateBucket` WHERE `scopeKind`='SESSION' AND `scopeHash`=? AND `operation`='TELEMETRY' AND `windowStart`=? AND `windowSeconds`=60",Integer.class,scope,time(window));
	}
	public void insert(UUID session,TelemetryEvent event,String webVersion,Instant now) {
		jdbc.update("""
			INSERT INTO `operationEvent` (`id`,`sessionId`,`callId`,`eventKey`,`category`,`code`,`isSuccess`,`latencyMs`,`networkType`,`webVersion`,`occurredAt`,`recordedAt`)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
			""",bin(UUID.randomUUID()),bin(session),bin(event.callId()),bin(event.eventId()),event.category(),event.code(),
			event.isSuccess(),event.latencyMs(),event.networkType(),webVersion,time(event.occurredAt()),time(now));
	}
	private static UUID uuid(byte[] value) { if(value==null)return null;var bytes=ByteBuffer.wrap(value);return new UUID(bytes.getLong(),bytes.getLong()); }
}

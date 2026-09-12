package com.safecall.service.call.repository;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.safecall.service.call.api.CallDtos.*;
import com.safecall.service.auth.repository.AuthRows.Session;

@Repository
public class CallRepository {
	private final JdbcTemplate jdbc;
	private final com.safecall.service.common.crypto.TransientKeys keys;
	private final com.safecall.service.call.service.CallPolicy policy;
	public CallRepository(JdbcTemplate jdbc,com.safecall.service.common.crypto.TransientKeys keys,com.safecall.service.call.service.CallPolicy policy) { this.jdbc=jdbc;this.keys=keys;this.policy=policy; }
	public record Call(UUID sessionId, UUID releaseId, Instant lastHeartbeatAt, byte[] pageKeyHash, CallView view) {
		public boolean isTerminal() { return Set.of("ENDED","FAILED").contains(view.state()); }
	}
	public record Grant(UUID id,int generation,String purpose,String keyRef,String status,byte[] tokenCipher,Instant createdAt,Instant newSessionExpiresAt,Instant expiresAt) {
		@Override public String toString() { return "Grant[status="+status+"]"; }
	}
	public record Prompt(UUID releaseId, String model, String apiVersion, String safety, String demographic,
		String guest, String base, String voice, String scenario, String counterpart) {}
	private static UUID uuid(ResultSet r,String field) throws SQLException {
		byte[] b=r.getBytes(field); if(b==null)return null;
		var bytes=ByteBuffer.wrap(b); return new UUID(bytes.getLong(),bytes.getLong());
	}
	private static Instant instant(ResultSet r,String field) throws SQLException {
		var t=r.getObject(field,LocalDateTime.class); return t==null ? null : t.toInstant(ZoneOffset.UTC);
	}
	private <T> T one(String sql,RowMapper<T> mapper,Object... args) {
		var rows=jdbc.query(sql,mapper,args); return rows.isEmpty() ? null : rows.getFirst();
	}
	private static final String CALL_SQL="SELECT c.*,p.`displayName` FROM `callSession` c JOIN `counterpart` p ON p.`code`=c.`counterpartCode` ";
	private static final RowMapper<Call> CALL=(r,n)->new Call(uuid(r,"sessionId"),uuid(r,"releaseId"),instant(r,"lastHeartbeatAt"),r.getBytes("pageKeyHash"),
		new CallView(uuid(r,"id"),uuid(r,"clientCallId"),r.getString("state"),r.getString("startMode"),r.getString("scenarioCode"),
			r.getString("counterpartCode"),r.getString("displayName"),instant(r,"createdAt"),instant(r,"ringingAt"),
			instant(r,"answeredAt"),instant(r,"endedAt"),r.getString("endReason"),instant(r,"leaseExpiresAt"),instant(r,"expiresAt"),r.getString("policyVersion"),r.getInt("maxResumeAttempts"),r.getInt("resumeDelayMs"),r.getLong("version")));
	public Call call(UUID id,boolean isLock) {
		return one(CALL_SQL+"WHERE c.`id`=?"+(isLock ? " FOR UPDATE OF c" : ""),CALL,bin(id));
	}
	public Call byClient(UUID session,UUID client) { return one(CALL_SQL+"WHERE c.`sessionId`=? AND c.`clientCallId`=?",CALL,bin(session),bin(client)); }
	public Call open(UUID session) { return one(CALL_SQL+"WHERE c.`sessionId`=? AND c.`activeMarker`=1 FOR UPDATE OF c",CALL,bin(session)); }
	public Grant grant(UUID id) {return grant(id,null);}
	public Grant grant(UUID callId,UUID grantId) {
		return one("SELECT * FROM `connectionGrant` WHERE `callId`=?"+(grantId==null?" ORDER BY `generation` DESC LIMIT 1":" AND `id`=?")+" FOR UPDATE",
			(r,n)->new Grant(uuid(r,"id"),r.getInt("generation"),r.getString("purpose"),r.getString("keyRef"),r.getString("status"),r.getBytes("tokenCipher"),instant(r,"createdAt"),instant(r,"newSessionExpiresAt"),instant(r,"expiresAt")),
			grantId==null?new Object[]{bin(callId)}:new Object[]{bin(callId),bin(grantId)});
	}
	public int resumeCount(UUID id){return jdbc.queryForObject("SELECT COUNT(*) FROM `connectionGrant` WHERE `callId`=? AND `purpose`='RESUME'",Integer.class,bin(id));}
	public UUID newGrant(UUID call,int generation,String purpose,Instant now){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO `connectionGrant` (`id`,`callId`,`generation`,`purpose`,`status`,`createdAt`) VALUES (?,?,?,?,'PENDING',?)",bin(id),bin(call),generation,purpose,time(now));return id;}

	public List<Prompt> prompts(UUID release,Instant now) {
		return jdbc.query("""
			SELECT r.*,p.`baseInstruction`,p.`voiceId`,p.`scenarioCode`,p.`counterpartCode`
			FROM `promptRelease` r JOIN `personaPrompt` p ON p.`releaseId`=r.`id`
			WHERE r.`publishedAt`<=? AND r.`validationRef` IS NOT NULL AND LENGTH(TRIM(r.`validationRef`))>0
			"""+(release==null ? " AND r.`status`='PUBLISHED'" : " AND r.`id`=? AND r.`status` IN ('PUBLISHED','RETIRED')"),
			(r,n)->new Prompt(uuid(r,"id"),r.getString("modelId"),r.getString("apiVersion"),r.getString("safetyInstruction"),
				r.getString("demographicRules"),r.getString("guestInstruction"),r.getString("baseInstruction"),r.getString("voiceId"),
				r.getString("scenarioCode"),r.getString("counterpartCode")),release==null ? new Object[]{time(now)} : new Object[]{time(now),bin(release)});
	}
	public List<Prompt> draftPrompts(UUID release) {
		return jdbc.query("""
			SELECT r.*,p.`baseInstruction`,p.`voiceId`,p.`scenarioCode`,p.`counterpartCode`
			FROM `promptRelease` r JOIN `personaPrompt` p ON p.`releaseId`=r.`id` WHERE r.`id`=?
			""",(r,n)->new Prompt(uuid(r,"id"),r.getString("modelId"),r.getString("apiVersion"),r.getString("safetyInstruction"),
				r.getString("demographicRules"),r.getString("guestInstruction"),r.getString("baseInstruction"),r.getString("voiceId"),
				r.getString("scenarioCode"),r.getString("counterpartCode")),bin(release));
	}
	public Set<String> scenarios() { return new HashSet<>(jdbc.queryForList("SELECT `code` FROM `scenario`",String.class)); }
	public void insert(UUID id,Session session,CreateCall body,UUID release,boolean demographic,boolean gender,Instant now,Instant expiry,byte[] pageKeyHash) {
		jdbc.update("""
			INSERT INTO `callSession` (`id`,`sessionId`,`clientCallId`,`startMode`,`releaseId`,`scenarioCode`,`counterpartCode`,
			`isDemographicApplied`,`isGenderAddressApplied`,`createdAt`,`lastHeartbeatAt`,`expiresAt`,`pageKeyHash`,`leaseExpiresAt`,`policyVersion`,`maxResumeAttempts`,`resumeDelayMs`)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			""",bin(id),bin(session.id()),bin(body.clientCallId()),body.startMode().name(),bin(release),body.scenarioCode(),body.counterpartCode(),
			demographic,gender,time(now),time(now),time(expiry),pageKeyHash,time(now.plusSeconds(policy.leaseSeconds()).isBefore(expiry)?now.plusSeconds(policy.leaseSeconds()):expiry),policy.version(),policy.maxResumeAttempts(),policy.resumeDelayMs());
		newGrant(id,1,"INITIAL",now);
	}
	public void preparing(UUID id) { jdbc.update("UPDATE `callSession` SET `state`='PREPARING',`version`=2 WHERE `id`=?",bin(id)); }
	public void flags(UUID id,boolean demographic,boolean gender) {
		jdbc.update("UPDATE `callSession` SET `isDemographicApplied`=?,`isGenderAddressApplied`=? WHERE `id`=?",demographic,gender,bin(id));
	}
	public void event(UUID id,UUID key,byte[] hash,String type,String state,Instant occurred,Instant recorded) {
		jdbc.update("""
			INSERT INTO `callEvent` (`id`,`callId`,`sequence`,`eventKey`,`requestHash`,`eventType`,`stateAfter`,`occurredAt`,`recordedAt`)
			SELECT ?,?,COALESCE(MAX(`sequence`),0)+1,?,?,?,?,?,? FROM `callEvent` WHERE `callId`=?
			""",bin(UUID.randomUUID()),bin(id),bin(key),hash,type,state,time(occurred),time(recorded),bin(id));
	}
	public byte[] eventHash(UUID id,UUID event) {
		return one("SELECT `requestHash` FROM `callEvent` WHERE `callId`=? AND `eventKey`=?",(r,n)->r.getBytes(1),bin(id),bin(event));
	}
	public byte[] createHash(UUID id) {
		return one("SELECT `requestHash` FROM `callEvent` WHERE `callId`=? AND `eventType`='CREATED'",(r,n)->r.getBytes(1),bin(id));
	}
	public void transition(UUID id,String state,Instant now) {
		String extra=switch(state) { case "RINGING" -> ",`ringingAt`=?"; case "ACTIVE" -> ",`answeredAt`=COALESCE(`answeredAt`,?)"; default -> ""; };
		jdbc.update("UPDATE `callSession` SET `state`=?,`version`=`version`+1"+extra+" WHERE `id`=?",
			extra.isEmpty() ? new Object[]{state,bin(id)} : new Object[]{state,time(now),bin(id)});
	}
	public void end(UUID id,String state,String reason,Instant now) {
		jdbc.update("UPDATE `callSession` SET `state`=?,`endReason`=?,`endedAt`=?,`version`=`version`+1 WHERE `id`=?",state,reason,time(now),bin(id));
		invalidate(id,"INVALIDATED");
	}
	public void invalidate(UUID id,String status) {
		var refs=jdbc.queryForList("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=? AND `keyRef` IS NOT NULL",String.class,bin(id));
		jdbc.update("UPDATE `connectionGrant` SET `status`=?,`tokenCipher`=NULL,`keyRef`=NULL WHERE `callId`=? AND `status` IN ('PENDING','ISSUING','READY')",status,bin(id));
		refs.forEach(keys::discardAfterCommit);
	}
	public void markUnknown(UUID grant){jdbc.update("UPDATE `connectionGrant` SET `status`='UNKNOWN',`tokenCipher`=NULL,`keyRef`=NULL WHERE `id`=?",bin(grant));}
	public void heartbeat(UUID id,Instant now,Instant lease) {jdbc.update("UPDATE `callSession` SET `lastHeartbeatAt`=?,`leaseExpiresAt`=? WHERE `id`=?",time(now),time(lease),bin(id));}
	public boolean claim(UUID grant,Instant now) {return jdbc.update("UPDATE `connectionGrant` SET `status`='ISSUING' WHERE `id`=? AND `status`='PENDING'",bin(grant))==1;}
	public void ready(UUID grant,byte[] cipher,String keyRef,Instant newExpiry,Instant expiry,Instant now) {
		jdbc.update("UPDATE `connectionGrant` SET `status`='READY',`tokenCipher`=?,`keyRef`=?,`newSessionExpiresAt`=?,`expiresAt`=?,`issuedAt`=? WHERE `id`=? AND `status`='ISSUING'",cipher,keyRef,time(newExpiry),time(expiry),time(now),bin(grant));
	}
	public void used(Grant grant,Instant now) {
		jdbc.update("UPDATE `connectionGrant` SET `status`='USED',`tokenCipher`=NULL,`keyRef`=NULL,`usedAt`=? WHERE `id`=? AND `status`='READY'",time(now),bin(grant.id()));
		keys.discardAfterCommit(grant.keyRef());
	}
	public List<UUID> pending() { return jdbc.query("SELECT `callId` FROM `connectionGrant` WHERE `status`='PENDING' ORDER BY `createdAt` LIMIT 100",(r,n)->uuid(r,"callId")); }
	public List<UUID> expired(Instant now) {
		return jdbc.query("""
			SELECT c.`id` FROM `callSession` c
			WHERE c.`activeMarker`=1 AND (c.`expiresAt`<=? OR c.`leaseExpiresAt`<=?
			OR EXISTS (SELECT 1 FROM `connectionGrant` g WHERE g.`callId`=c.`id` AND
			((g.`status`='READY' AND g.`newSessionExpiresAt`<=?)
			OR (g.`status`='ISSUING' AND g.`createdAt`<=?)))) ORDER BY c.`createdAt` LIMIT 100
			""",(r,n)->uuid(r,"id"),time(now),time(now),time(now),time(now.minusSeconds(policy.issueTimeoutSeconds())));
	}
	public boolean hasConsent(UUID user,String code,Instant now) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT EXISTS(SELECT 1 FROM `serviceDocument` d WHERE d.`code`=? AND d.`isCurrent`=1
			AND d.`isConsent`=1 AND d.`publishedAt`<=? AND EXISTS(SELECT 1 FROM `consentEvent` e WHERE
			e.`id`=(SELECT e2.`id` FROM `consentEvent` e2 WHERE e2.`userId`=? AND e2.`documentCode`=d.`code`
			ORDER BY e2.`recordedAt` DESC,e2.`id` DESC LIMIT 1) AND e.`action`='GRANTED' AND e.`documentVersion`=d.`version`))
			""",Boolean.class,code,time(now),bin(user)));
	}
	public int rate(byte[] scope,String kind,String operation,Instant window,int seconds,boolean increment) {
		if(increment) jdbc.update("""
			INSERT INTO `rateBucket` (`scopeKind`,`scopeHash`,`operation`,`windowStart`,`windowSeconds`,`usedCount`,`expiresAt`)
			VALUES (?,?,?,?,?,1,?) ON DUPLICATE KEY UPDATE `usedCount`=`usedCount`+1
			""",kind,scope,operation,time(window),seconds,time(window.plusSeconds(seconds)));
		Integer count=one("SELECT `usedCount` FROM `rateBucket` WHERE `scopeKind`=? AND `scopeHash`=? AND `operation`=? AND `windowStart`=? AND `windowSeconds`=?",
			(r,n)->r.getInt(1),kind,scope,operation,time(window),seconds);
		return count==null ? 0 : count;
	}
}

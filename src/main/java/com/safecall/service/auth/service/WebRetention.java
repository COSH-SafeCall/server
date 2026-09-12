package com.safecall.service.auth.service;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.crypto.TransientKeys;

/** T06 retention; acquire account/session locks before cascading call data. */
@Component
public class WebRetention {
	private final JdbcTemplate jdbc;
	private final AuthRepository auth;
	private final SecretCrypto crypto;
	private final TransientKeys keys;
	private final Clock clock;
	private final TransactionTemplate transaction;
	public WebRetention(JdbcTemplate jdbc,AuthRepository auth,SecretCrypto crypto,TransientKeys keys,Clock clock,PlatformTransactionManager manager) {
		this.jdbc=jdbc;this.auth=auth;this.crypto=crypto;this.keys=keys;this.clock=clock;this.transaction=new TransactionTemplate(manager);
	}
	@Scheduled(fixedDelayString="${app.auth.cleanup-delay-ms}",initialDelayString="${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			var sessions=jdbc.queryForList("""
				SELECT `id` FROM `webSession` WHERE
				(`kind`<>'KAKAO' AND COALESCE(`revokedAt`,`expiresAt`)<=?) OR
				(`kind`='KAKAO' AND COALESCE(`revokedAt`,`expiresAt`)<=?) LIMIT 100
				""",byte[].class,time(clock.instant().minusSeconds(3600)),time(clock.instant().minusSeconds(2592000)));
			for(byte[] id:sessions)transaction.executeWithoutResult(tx -> deleteSession(uuid(id)));
			var calls=jdbc.queryForList("SELECT `id`,`sessionId` FROM `callSession` WHERE `endedAt`<=? LIMIT 100",time(clock.instant().minusSeconds(2592000)));
			for(var call:calls)transaction.executeWithoutResult(tx -> {
				auth.lockSession(uuid((byte[])call.get("sessionId")));
				byte[] id=(byte[])call.get("id");
				var locked=jdbc.queryForList("SELECT `id` FROM `callSession` WHERE `id`=? AND `endedAt`<=? FOR UPDATE",byte[].class,id,time(clock.instant().minusSeconds(2592000)));
				if(!locked.isEmpty()){discardCallKeys(id);removeRate("RENEW_RATE",uuid(id));jdbc.update("DELETE FROM `callSession` WHERE `id`=?",id);}
			});
			transaction.executeWithoutResult(tx -> {
				jdbc.update("DELETE FROM `operationEvent` WHERE `recordedAt`<=?",time(clock.instant().minusSeconds(1209600)));
				jdbc.update("DELETE FROM `deletionJob` WHERE `status`='COMPLETED' AND `completedAt`<=?",time(clock.instant().minusSeconds(2592000)));
			});
		} catch(RuntimeException ex) {org.slf4j.LoggerFactory.getLogger(getClass()).error("Web retention will retry its remaining work.");}
	}
	private void deleteSession(UUID id) {
		var session=auth.lockSession(id);if(session==null)return;
		var now=clock.instant();
		boolean due=Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT IF(`kind`='KAKAO',COALESCE(`revokedAt`,`expiresAt`)<=?,COALESCE(`revokedAt`,`expiresAt`)<=?) FROM `webSession` WHERE `id`=?
			""",Boolean.class,time(now.minusSeconds(2592000)),time(now.minusSeconds(3600)),bin(id)));
		if(!due)return;
		if(session.status().equals("ACTIVE"))auth.endSession(session,now,"EXPIRED","SESSION_EXPIRED",crypto.hash("SERVER_EVENT","SESSION_EXPIRED"));
		for(byte[] call:jdbc.queryForList("SELECT `id` FROM `callSession` WHERE `sessionId`=? FOR UPDATE",byte[].class,bin(id))) {
			discardCallKeys(call);removeRate("RENEW_RATE",uuid(call));
		}
		removeRate("CALL_RATE",id);
		jdbc.update("DELETE FROM `webSession` WHERE `id`=?",bin(id));
	}
	private void discardCallKeys(byte[] id) {jdbc.queryForList("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=? AND `keyRef` IS NOT NULL",String.class,id).forEach(keys::discardAfterCommit);}
	private void removeRate(String domain,UUID id) {jdbc.update("DELETE FROM `rateBucket` WHERE `scopeHash`=?",crypto.hash(domain,id.toString()));}
	private static UUID uuid(byte[] id) {var b=ByteBuffer.wrap(id);return new UUID(b.getLong(),b.getLong());}
}

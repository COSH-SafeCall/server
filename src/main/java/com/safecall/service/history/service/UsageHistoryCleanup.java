package com.safecall.service.history.service;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.*;
import com.safecall.service.user.repository.UserRepository;

@Component
public class UsageHistoryCleanup {
	private final JdbcTemplate jdbc;
	private final AuthRepository auth;
	private final UserRepository users;
	private final SecretCrypto crypto;
	private final TransientKeys keys;
	private final Clock clock;
	private final LocalDeletionTransactions transaction;
	public UsageHistoryCleanup(JdbcTemplate jdbc,AuthRepository auth,UserRepository users,SecretCrypto crypto,TransientKeys keys,Clock clock,LocalDeletionTransactions transaction) {
		this.jdbc=jdbc;this.auth=auth;this.users=users;this.crypto=crypto;this.keys=keys;this.clock=clock;this.transaction=transaction;
	}
	@Scheduled(fixedDelayString="${app.auth.cleanup-delay-ms}",initialDelayString="${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			var jobs=jdbc.queryForList("SELECT `id`,`userId` FROM `deletionJob` WHERE `scope`='USAGE_HISTORY' AND `status`='PENDING' ORDER BY `requestedAt` LIMIT 100");
			for(var job:jobs) if(job.get("userId")!=null) {
				UUID id=uuid((byte[])job.get("id")),user=uuid((byte[])job.get("userId"));
				transaction.execute(id,user,()->clean(id,user));
			}
		} catch(RuntimeException ex) {org.slf4j.LoggerFactory.getLogger(getClass()).error("Usage history cleanup will retry pending work.");}
	}
	private void clean(UUID id,UUID user) {
		var account=auth.user(user,true);if(account==null || account.status().equals("DELETION_PENDING"))return;
		var rows=jdbc.queryForList("SELECT `cutoffAt` FROM `deletionJob` WHERE `id`=? AND `status`='PENDING' AND `requestedAt`<=? FOR UPDATE",bin(id),time(clock.instant().minusSeconds(60)));
		if(rows.isEmpty() || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM `apiIdempotency` WHERE `resourceId`=? AND `responseExpiresAt`>?)",Boolean.class,bin(id),time(clock.instant()))))return;
		for(UUID session:users.sessions(user))auth.lockSession(session);
		var calls=jdbc.queryForList("SELECT c.`id` FROM `callSession` c JOIN `webSession` s ON s.`id`=c.`sessionId` WHERE s.`userId`=? AND c.`createdAt`<=? AND c.`state` IN ('ENDED','FAILED') FOR UPDATE",byte[].class,bin(user),rows.getFirst().get("cutoffAt"));
		for(byte[] call:calls) {
			jdbc.queryForList("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=? AND `keyRef` IS NOT NULL",String.class,call).forEach(keys::discardAfterCommit);
			jdbc.update("DELETE FROM `rateBucket` WHERE `scopeHash`=?",crypto.hash("RENEW_RATE",uuid(call).toString()));
			// 관련 callEvent·connectionGrant·operationEvent는 FK CASCADE로 함께 제거된다.
			jdbc.update("DELETE FROM `callSession` WHERE `id`=?",call);
		}
		jdbc.update("UPDATE `deletionJob` SET `status`='COMPLETED',`completedAt`=? WHERE `id`=?",time(clock.instant()),bin(id));
	}
	private static UUID uuid(byte[] bytes) {var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
}

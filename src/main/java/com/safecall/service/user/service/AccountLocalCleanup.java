package com.safecall.service.user.service;

import static com.safecall.service.auth.repository.AuthRepository.bin;
import static com.safecall.service.auth.repository.AuthRepository.time;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.crypto.TransientKeys;
import com.safecall.service.history.service.LocalDeletionTransactions;
import com.safecall.service.user.repository.UserRepository;

@Component
public class AccountLocalCleanup {
	private final JdbcTemplate jdbc;
	private final AuthRepository auth;
	private final UserRepository users;
	private final SecretCrypto crypto;
	private final TransientKeys temporaryKeys;
	private final Clock clock;
	private final LocalDeletionTransactions deletionTransactions;

	public AccountLocalCleanup(JdbcTemplate jdbc, AuthRepository auth, UserRepository users, SecretCrypto crypto,
		TransientKeys temporaryKeys, Clock clock, LocalDeletionTransactions deletionTransactions) {
		this.jdbc = jdbc;
		this.auth = auth;
		this.users = users;
		this.crypto = crypto;
		this.temporaryKeys = temporaryKeys;
		this.clock = clock;
		this.deletionTransactions = deletionTransactions;
	}

	@Scheduled(scheduler = "cleanupScheduler", fixedDelayString = "${app.auth.cleanup-delay-ms}", initialDelayString = "${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			var jobs = jdbc.queryForList("SELECT `id`,`userId` FROM `deletionJob` WHERE `scope`='ACCOUNT' AND `status`='PENDING' AND `requestedAt`<=? LIMIT 100",
				time(clock.instant().minusSeconds(60)));
			for (var job : jobs) {
				if (job.get("userId") != null) {
					deletionTransactions.execute(uuid((byte[]) job.get("id")), uuid((byte[]) job.get("userId")),
						() -> deleteLocal((byte[]) job.get("id"), (byte[]) job.get("userId")));
				}
			}
		} catch (RuntimeException exception) {
			org.slf4j.LoggerFactory.getLogger(getClass()).error("Account deletion will retry its remaining work.");
		}
	}

	private void deleteLocal(byte[] jobId, byte[] owner) {
		UUID userId = uuid(owner);
		var user = auth.user(userId, true);
		if (user == null) {
			return;
		}
		var jobs = jdbc.queryForList("SELECT `status` FROM `deletionJob` WHERE `id`=? FOR UPDATE", jobId);
		if (jobs.isEmpty() || !"PENDING".equals(jobs.getFirst().get("status"))) {
			return;
		}
		if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM `apiIdempotency` WHERE `resourceId`=? AND `responseExpiresAt`>?)",
			Boolean.class, jobId, time(clock.instant())))) {
			return;
		}
		removeRate(crypto.hash("CALL_RATE", userId.toString()));
		for (UUID sessionId : users.sessions(userId)) {
			auth.lockSession(sessionId);
			removeRate(crypto.hash("CALL_RATE", sessionId.toString()));
			removeRate(crypto.hash("TELEMETRY_RATE", sessionId.toString()));
			for (byte[] call : jdbc.queryForList("SELECT `id` FROM `callSession` WHERE `sessionId`=?", byte[].class, bin(sessionId))) {
				removeRate(crypto.hash("RENEW_RATE", uuid(call).toString()));
			}
		}
		jdbc.queryForList("SELECT g.`keyRef` FROM `connectionGrant` g JOIN `callSession` c ON c.`id`=g.`callId` JOIN `webSession` s ON s.`id`=c.`sessionId` WHERE s.`userId`=? AND g.`keyRef` IS NOT NULL",
			String.class, owner).forEach(temporaryKeys::discardAfterCommit);
		jdbc.update("DELETE FROM `webSession` WHERE `userId`=?", owner);
		jdbc.update("DELETE FROM `appUser` WHERE `id`=?", owner);
		jdbc.update("UPDATE `deletionJob` SET `status`='COMPLETED',`completedAt`=?,`errorCode`=NULL WHERE `id`=?", time(clock.instant()), jobId);
		temporaryKeys.discardAfterCommit(user.keyRef());
	}

	private void removeRate(byte[] hash) {
		jdbc.update("DELETE FROM `rateBucket` WHERE `scopeHash`=?", hash);
	}

	private static UUID uuid(byte[] bytes) {
		var buffer = ByteBuffer.wrap(bytes);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}

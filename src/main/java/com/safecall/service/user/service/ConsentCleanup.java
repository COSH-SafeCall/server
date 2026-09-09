package com.safecall.service.user.service;
import static com.safecall.service.auth.repository.AuthRepository.bin;
import static com.safecall.service.auth.repository.AuthRepository.time;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.safecall.service.auth.repository.AuthRepository;

/** 계정 삭제는 6장 worker에 맡기고, 동의 철회의 AI/위치 정리만 처리한다. */
@Component
public class ConsentCleanup {
	private final JdbcTemplate jdbc;
	private final AuthRepository auth;
	private final Clock clock;
	private final TransactionTemplate transaction;
	public ConsentCleanup(JdbcTemplate jdbc,AuthRepository auth,Clock clock,PlatformTransactionManager manager) {
		this.jdbc=jdbc; this.auth=auth; this.clock=clock; this.transaction=new TransactionTemplate(manager);
	}
	@Scheduled(fixedDelayString="${app.auth.cleanup-delay-ms}",initialDelayString="${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			var jobs=jdbc.queryForList("SELECT `id`,`userId`,`scope` FROM `deletionJob` WHERE `pendingMarker`=1 AND `scope` IN ('AI_DATA','LOCATION_DATA') ORDER BY `requestedAt` LIMIT 100");
			for (var job:jobs) transaction.executeWithoutResult(status -> {
				byte[] owner=(byte[])job.get("userId");
				if (owner==null) return;
				var bytes=ByteBuffer.wrap(owner); UUID user=new UUID(bytes.getLong(),bytes.getLong());
				var account=auth.user(user,true);
				if (account==null || account.status().equals("DELETION_PENDING")) return;
				byte[] id=(byte[])job.get("id");
				var locked=jdbc.queryForList("SELECT `pendingMarker` FROM `deletionJob` WHERE `id`=? FOR UPDATE",id);
				if (locked.isEmpty() || locked.getFirst().get("pendingMarker")==null) return;
				if (job.get("scope").equals("AI_DATA")) {
					jdbc.update("DELETE FROM `callSession` WHERE `sessionId` IN (SELECT `id` FROM `deviceSession` WHERE `userId`=?)",bin(user));
					jdbc.update("DELETE FROM `operationEvent` WHERE `userId`=? AND `category` IN ('CALL','AUDIO')",bin(user));
					jdbc.update("UPDATE `appUser` SET `genderCipher`=NULL,`birthDateCipher`=NULL,`genderSource`='UNKNOWN',`birthDateSource`='UNKNOWN',`version`=`version`+1,`updatedAt`=? WHERE `id`=?",time(clock.instant()),bin(user));
				}
				jdbc.update("UPDATE `deletionJob` SET `status`='COMPLETED',`completedAt`=?,`cleanupCipher`=NULL WHERE `id`=?",time(clock.instant()),id);
			});
		} catch (RuntimeException exception) {
			org.slf4j.LoggerFactory.getLogger(getClass()).error("Consent cleanup failed; retrying next cycle.");
		}
	}
}

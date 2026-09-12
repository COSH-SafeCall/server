package com.safecall.service.user.service;

import static com.safecall.service.auth.repository.AuthRepository.bin;
import static com.safecall.service.auth.repository.AuthRepository.time;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.*;
import com.safecall.service.user.api.UserDtos.DeletionReceipt;
import com.safecall.service.user.repository.UserRepository;

/** Shared local cleanup for U06 ACCOUNT and abandoned onboarding. External unlink belongs to the chapter 6 worker. */
@Component
public class AccountLocalCleanup {
	private final JdbcTemplate jdbc;
	private final AuthRepository auth;
	private final UserRepository users;
	private final SecretCrypto crypto;
	private final UserKeyStore userKeys;
	private final TransientKeys temporaryKeys;
	private final JsonMapper mapper;
	private final Clock clock;
	private final TransactionTemplate transaction;

	public AccountLocalCleanup(JdbcTemplate jdbc,AuthRepository auth,UserRepository users,SecretCrypto crypto,
		UserKeyStore userKeys,TransientKeys temporaryKeys,JsonMapper mapper,Clock clock,PlatformTransactionManager manager) {
		this.jdbc=jdbc;this.auth=auth;this.users=users;this.crypto=crypto;this.userKeys=userKeys;
		this.temporaryKeys=temporaryKeys;this.mapper=mapper;this.clock=clock;this.transaction=new TransactionTemplate(manager);
	}

	@Scheduled(fixedDelayString="${app.auth.cleanup-delay-ms}",initialDelayString="${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			var abandoned=jdbc.queryForList("SELECT `id` FROM `appUser` WHERE `status`='ONBOARDING' AND `createdAt`<=? LIMIT 100",
				byte[].class,time(clock.instant().minusSeconds(86400)));
			for(byte[] id:abandoned)transaction.executeWithoutResult(tx -> queueAbandoned(uuid(id)));
			// Preserve the complete 60 second receipt replay window before account rows disappear.
			var jobs=jdbc.queryForList("SELECT `id`,`userId` FROM `deletionJob` WHERE `scope`='ACCOUNT' AND `status`='PENDING' AND `requestedAt`<=? LIMIT 100",
				time(clock.instant().minusSeconds(60)));
			for(var job:jobs) {
				try {transaction.executeWithoutResult(tx -> deleteLocal((byte[])job.get("id"),(byte[])job.get("userId")));}
				catch(RuntimeException ex) {failure();}
			}
		} catch(RuntimeException ex) {failure();}
	}

	private void queueAbandoned(UUID id) {
		var user=auth.user(id,true);
		if(user==null || !user.status().equals("ONBOARDING"))return;
		boolean expired=Boolean.TRUE.equals(jdbc.queryForObject("SELECT `createdAt`<=? FROM `appUser` WHERE `id`=?",Boolean.class,time(clock.instant().minusSeconds(86400)),bin(id)));
		if(!expired || users.pending(id,"ACCOUNT"))return;
		var now=clock.instant();String receipt=crypto.randomToken();
		users.deletion(id,new DeletionReceipt(UUID.randomUUID(),"ACCOUNT","PENDING",receipt,now.plusSeconds(86400),now.plusSeconds(2592000)),crypto.hash("DELETION_RECEIPT",receipt),now);
		for(UUID session:users.sessions(id)) {
			var locked=auth.lockSession(session);
			if(locked!=null && locked.status().equals("ACTIVE"))auth.endSession(locked,now,"REVOKED","DATA_DELETION",crypto.hash("SERVER_EVENT","DATA_DELETION"));
		}
	}

	private void deleteLocal(byte[] jobId,byte[] owner) {
		if(owner==null)return;
		UUID userId=uuid(owner);var user=auth.user(userId,true);
		if(user==null)return;
		var jobs=jdbc.queryForList("SELECT `status` FROM `deletionJob` WHERE `id`=? FOR UPDATE",jobId);
		if(jobs.isEmpty() || !jobs.getFirst().get("status").equals("PENDING"))return;
		// External cleanup must remain possible after the personal decryption key is discarded.
		byte[] subjectCipher=jdbc.queryForObject("SELECT `kakaoSubjectCipher` FROM `appUser` WHERE `id`=?",byte[].class,owner);
		String subject=new String(crypto.open(userKeys.read(user.keyRef()),userId+":subject",subjectCipher),StandardCharsets.UTF_8);
		String cleanupRef=temporaryKeys.create(UUID.randomUUID());
		byte[] cleanupCipher=crypto.seal(temporaryKeys.read(cleanupRef),"ACCOUNT_CLEANUP:"+uuid(jobId),
			mapper.writeValueAsBytes(Map.of("subject",subject,"userKeyRef",user.keyRef())));
		removeRate(crypto.hash("CALL_RATE",userId.toString()));
		for(UUID sessionId:users.sessions(userId)) {
			auth.lockSession(sessionId);
			removeRate(crypto.hash("CALL_RATE",sessionId.toString()));
			for(byte[] call:jdbc.queryForList("SELECT `id` FROM `callSession` WHERE `sessionId`=?",byte[].class,bin(sessionId)))
				removeRate(crypto.hash("RENEW_RATE",uuid(call).toString()));
		}
		jdbc.queryForList("SELECT g.`keyRef` FROM `connectionGrant` g JOIN `callSession` c ON c.`id`=g.`callId` JOIN `webSession` s ON s.`id`=c.`sessionId` WHERE s.`userId`=? AND g.`keyRef` IS NOT NULL",String.class,owner)
			.forEach(temporaryKeys::discardAfterCommit);
		jdbc.update("DELETE FROM `webSession` WHERE `userId`=?",owner);
		jdbc.update("UPDATE `deletionJob` SET `status`='COMPLETED',`completedAt`=?,`cleanupCipher`=NULL,`cleanupKeyRef`=NULL WHERE `userId`=? AND `scope` IN ('AI_DATA','LOCATION_DATA','USAGE_HISTORY') AND `pendingMarker`=1",time(clock.instant()),owner);
		jdbc.update("DELETE FROM `appUser` WHERE `id`=?",owner);
		jdbc.update("UPDATE `deletionJob` SET `status`='LOCAL_DELETED',`cleanupCipher`=?,`cleanupKeyRef`=? WHERE `id`=?",cleanupCipher,cleanupRef,jobId);
		temporaryKeys.discardAfterCommit(user.keyRef());
		// Do not mark COMPLETED here: Kakao unlink and external cleanup have not completed.
	}

	private void removeRate(byte[] hash) {jdbc.update("DELETE FROM `rateBucket` WHERE `scopeHash`=?",hash);}
	private static UUID uuid(byte[] bytes) {var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
	private void failure() {
		// A failed/uncertain commit is re-read on the next cycle; never claim that deleted data survived.
		org.slf4j.LoggerFactory.getLogger(getClass()).error("Local account cleanup will recheck its transaction outcome.");
	}
}

package com.safecall.service.history.service;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.kakao.KakaoUnlinkClient;
import com.safecall.service.common.crypto.*;

/** 짧은 DB 트랜잭션에서 작업을 선점하고, 키 폐기와 카카오 호출은 DB 잠금 없이 수행한다. */
@Component
public class AccountExternalCleanup {
	private final JdbcTemplate jdbc;
	private final SecretCrypto crypto;
	private final UserKeyStore keys;
	private final TransientKeys temporaryKeys;
	private final KakaoUnlinkClient kakao;
	private final JsonMapper mapper;
	private final Clock clock;
	private final TransactionTemplate transaction;
	private record Claim(UUID id,String keyRef,byte[] cipher,String subject,String userKeyRef) {
		@Override public String toString() { return "Claim[redacted]"; }
	}
	public AccountExternalCleanup(JdbcTemplate jdbc,SecretCrypto crypto,UserKeyStore keys,TransientKeys temporaryKeys,
		KakaoUnlinkClient kakao,JsonMapper mapper,Clock clock,PlatformTransactionManager manager) {
		this.jdbc=jdbc;this.crypto=crypto;this.keys=keys;this.temporaryKeys=temporaryKeys;this.kakao=kakao;this.mapper=mapper;this.clock=clock;
		transaction=new TransactionTemplate(manager);
	}
	@Scheduled(fixedDelayString="${app.auth.cleanup-delay-ms}",initialDelayString="${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			for(byte[] id:jdbc.queryForList("SELECT `id` FROM `deletionJob` WHERE `scope`='ACCOUNT' AND `status`='LOCAL_DELETED' ORDER BY `requestedAt` LIMIT 100",byte[].class))runOne(uuid(id));
		} catch(RuntimeException exception) { log(); }
	}
	public void runOne(UUID id) {
		Claim claim;
		try { claim=transaction.execute(tx->claim(id)); } catch(RuntimeException exception) { log();return; }
		if(claim==null)return;
		try {
			keys.discard(claim.userKeyRef());
			kakao.unlink(claim.subject());
			transaction.executeWithoutResult(tx->{
				int updated=jdbc.update("""
					UPDATE `deletionJob` SET `status`='COMPLETED',`completedAt`=?,`errorCode`=NULL,
					`cleanupCipher`=NULL,`cleanupKeyRef`=NULL,`accountSubjectHash`=NULL
					WHERE `id`=? AND `status`='LOCAL_DELETED' AND `cleanupCipher`=?
					""",time(clock.instant()),bin(id),claim.cipher());
				if(updated==1)temporaryKeys.discardAfterCommit(claim.keyRef());
			});
		} catch(RuntimeException exception) {
			// 선점 만료(60초) 후 상태를 재조회해 재시도한다. 커밋 결과 불명도 FAILED로 바꾸지 않는다.
			log();
		}
	}
	private Claim claim(UUID id) {
		var rows=jdbc.queryForList("SELECT `cleanupKeyRef`,`cleanupCipher` FROM `deletionJob` WHERE `id`=? AND `status`='LOCAL_DELETED' FOR UPDATE",bin(id));
		if(rows.isEmpty())return null;
		String ref=(String)rows.getFirst().get("cleanupKeyRef");byte[] key=keys.read(ref);
		var payload=mapper.readTree(crypto.open(key,"ACCOUNT_CLEANUP:"+id,(byte[])rows.getFirst().get("cleanupCipher")));
		if(payload.has("leaseUntil") && Instant.parse(payload.path("leaseUntil").asString()).isAfter(clock.instant()))return null;
		String subject=payload.path("subject").asString(),userKeyRef=payload.path("userKeyRef").asString();
		byte[] cipher=crypto.seal(key,"ACCOUNT_CLEANUP:"+id,mapper.writeValueAsBytes(Map.of("subject",subject,"userKeyRef",userKeyRef,
			"leaseUntil",clock.instant().plusSeconds(60).toString())));
		jdbc.update("UPDATE `deletionJob` SET `cleanupCipher`=? WHERE `id`=?",cipher,bin(id));
		return new Claim(id,ref,cipher,subject,userKeyRef);
	}
	private static UUID uuid(byte[] bytes) {var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
	private void log() { org.slf4j.LoggerFactory.getLogger(getClass()).error("Account external cleanup remains pending and will be retried."); }
}

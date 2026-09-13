package com.safecall.service.history.service;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.UserKeyStore;

/** 확정 롤백에만 FAILED를 기록한다. 커밋 결과 불명은 다음 주기에 DB 상태를 재조회한다. */
@Component
public class LocalDeletionTransactions {
	private final TransactionTemplate transaction;
	private final AuthRepository auth;
	private final JdbcTemplate jdbc;
	private final UserKeyStore keys;
	private final com.safecall.service.common.crypto.SecretCrypto crypto;
	private final com.safecall.service.common.crypto.TransientKeys temporaryKeys;
	public LocalDeletionTransactions(PlatformTransactionManager manager,AuthRepository auth,JdbcTemplate jdbc,UserKeyStore keys,
		com.safecall.service.common.crypto.SecretCrypto crypto,com.safecall.service.common.crypto.TransientKeys temporaryKeys) {
		transaction=new TransactionTemplate(manager);this.auth=auth;this.jdbc=jdbc;this.keys=keys;
		this.crypto=crypto;this.temporaryKeys=temporaryKeys;
	}
	public void execute(UUID id,UUID user,Runnable action) {
		var outcome=new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
		try {
			transaction.executeWithoutResult(tx->{
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override public void afterCompletion(int status) { outcome.set(status); }
				});
				action.run();
			});
		} catch (RuntimeException exception) {
			if(outcome.get()==TransactionSynchronization.STATUS_ROLLED_BACK) {
				try { transaction.executeWithoutResult(tx->failed(id,user)); }
				catch(RuntimeException ignored) { log(); }
			} else log();
		}
	}
	private void failed(UUID id,UUID user) {
		var account=auth.user(user,true);if(account==null)return;
		var jobs=jdbc.queryForList("SELECT `scope`,`status`,`cleanupCipher`,`cleanupKeyRef` FROM `deletionJob` WHERE `id`=? AND `userId`=? FOR UPDATE",bin(id),bin(user));
		if(jobs.isEmpty() || !java.util.Set.of("PENDING","PROCESSING").contains(jobs.getFirst().get("status")))return;
		keys.read(account.keyRef()); // 복호화 키 유지까지 확인한 뒤에만 데이터 유지 안내가 가능한 상태로 전환한다.
		boolean isAccount=jobs.getFirst().get("scope").equals("ACCOUNT");
		String ref=(String)jobs.getFirst().get("cleanupKeyRef"),previous=null;
		if(isAccount) {
			// 이전 버전 작업에 복구 자료가 없으면 추측하여 계정을 복구하지 않고 PENDING 재시도를 유지한다.
			if(ref==null)return;
			previous=new String(crypto.open(keys.read(ref),"DELETION_ROLLBACK:"+id,(byte[])jobs.getFirst().get("cleanupCipher")),java.nio.charset.StandardCharsets.UTF_8);
			if(!java.util.Set.of("ACTIVE","ONBOARDING").contains(previous))return;
		}
		jdbc.update("UPDATE `deletionJob` SET `status`='FAILED',`errorCode`='LOCAL_DELETION_FAILED',`accountSubjectHash`=NULL,`cleanupCipher`=NULL,`cleanupKeyRef`=NULL WHERE `id`=?",bin(id));
		if(isAccount)jdbc.update("UPDATE `appUser` SET `status`=?,`version`=`version`+1 WHERE `id`=? AND `status`='DELETION_PENDING'",previous,bin(user));
		temporaryKeys.discardAfterCommit(ref);
	}
	private void log() { org.slf4j.LoggerFactory.getLogger(getClass()).error("Deletion transaction outcome will be rechecked; no data-retention claim made."); }
}

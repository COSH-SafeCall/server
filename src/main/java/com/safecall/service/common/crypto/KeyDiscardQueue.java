package com.safecall.service.common.crypto;

import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static com.safecall.service.auth.repository.AuthRepository.*;

/** Durable deletion intent; no foreign key to rows whose removal must not erase the intent. */
@Component
public class KeyDiscardQueue {
	private final JdbcTemplate jdbc;
	private final UserKeyStore keys;
	private final Clock clock;
	private final TransactionTemplate independent;
	private final KeyCreationJournal creationJournal;
	public KeyDiscardQueue(JdbcTemplate jdbc,UserKeyStore keys,Clock clock,PlatformTransactionManager manager,KeyCreationJournal creationJournal){
		this.jdbc=jdbc;this.keys=keys;this.clock=clock;
		this.creationJournal=creationJournal;
		independent=new TransactionTemplate(manager);
		independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}
	public UUID enqueue(String ref){
		if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Key discard requires a transaction.");
		UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO `keyDiscardJob` (`id`,`keyRef`,`createdAt`,`nextAttemptAt`) VALUES (?,?,?,?)",bin(id),ref,time(clock.instant()),time(clock.instant()));
		return id;
	}
	public UUID prepareCreation(String ref){
		if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Key creation requires a transaction.");
		// Commit the recovery intent BEFORE the external side effect.
		return creationJournal.prepare(ref,clock.instant());
	}
	public void enlistCreation(UUID job){
		if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Key creation requires a transaction.");
		// This delete keeps the row locked until the caller's commit/rollback. A cleanup worker
		// cannot delete a key while it is being created. If cleanup won first, do not create it.
		if(jdbc.update("DELETE FROM `keyDiscardJob` WHERE `id`=?",bin(job))!=1)throw new IllegalStateException("Key creation recovery intent was already consumed.");
	}
	public void attempt(UUID id){
		try {
			independent.executeWithoutResult(status->{
				var refs=jdbc.queryForList("SELECT `keyRef` FROM `keyDiscardJob` WHERE `id`=? FOR UPDATE",String.class,bin(id));
				if(refs.isEmpty())return;
				keys.discard(refs.getFirst()); // Idempotent: a crash after this call safely repeats it.
				jdbc.update("DELETE FROM `keyDiscardJob` WHERE `id`=?",bin(id));
			});
		}catch(RuntimeException ex){
			org.slf4j.LoggerFactory.getLogger(getClass()).error("Key discard deferred; durable job retained.");
			try {
				independent.executeWithoutResult(status->jdbc.update("UPDATE `keyDiscardJob` SET `nextAttemptAt`=? WHERE `id`=?",time(clock.instant().plusSeconds(30)),bin(id)));
			}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).error("Key discard rescheduling unavailable; durable job retained.");}
		}
	}
	@Scheduled(scheduler="cleanupScheduler",fixedDelayString="${app.crypto.discard-delay-ms:1000}",initialDelayString="${app.crypto.discard-delay-ms:1000}")
	public void run(){
		var jobs=jdbc.query("SELECT `id` FROM `keyDiscardJob` WHERE `nextAttemptAt`<=? ORDER BY `nextAttemptAt`,`id` LIMIT 100",(r,n)->{
			var bytes=java.nio.ByteBuffer.wrap(r.getBytes("id"));return new UUID(bytes.getLong(),bytes.getLong());
		},time(clock.instant()));
		jobs.forEach(this::attempt);
	}
}

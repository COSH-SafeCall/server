package com.safecall.service.common.crypto;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
/** Coordinates DB-external keys with transaction outcomes; unknown outcomes retain the keys. */
@Component
public class TransientKeys {
	private final UserKeyStore keys;
	private final KeyDiscardQueue queue;
	public TransientKeys(UserKeyStore keys,KeyDiscardQueue queue){this.keys=keys;this.queue=queue;}
	public String create(UUID id){
		if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Key creation requires a transaction.");
		UUID allocation=UUID.randomUUID();String ref=keys.reference(allocation);
		UUID job=queue.prepareCreation(ref);queue.enlistCreation(job);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
		@Override public void afterCompletion(int status){
			if(status==STATUS_ROLLED_BACK)queue.attempt(job);
			else if(status==STATUS_UNKNOWN)org.slf4j.LoggerFactory.getLogger(TransientKeys.class).error("Key retained until the transaction outcome is reconciled.");
		}
	});
		if(!ref.equals(keys.create(allocation)))throw new IllegalStateException("Key store violated its stable reference contract.");
		return ref;}
	public byte[] read(String ref){return keys.read(ref);}
	public void discardAfterCommit(String ref){if(ref==null)return;UUID job=queue.enqueue(ref);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
		@Override public void afterCommit(){queue.attempt(job);}
	});}
}

package com.safecall.service.common.crypto;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
/** Coordinates DB-external keys with transaction outcomes; unknown outcomes retain the keys. */
@Component
public class TransientKeys {
	private final UserKeyStore keys;
	public TransientKeys(UserKeyStore keys){this.keys=keys;}
	public String create(UUID id){
		if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Key creation requires a transaction.");
		String ref=keys.create(id);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
		@Override public void afterCompletion(int status){
			if(status==STATUS_ROLLED_BACK)discard(ref);
			else if(status==STATUS_UNKNOWN)org.slf4j.LoggerFactory.getLogger(TransientKeys.class).error("Key retained until the transaction outcome is reconciled.");
		}
	});return ref;}
	public byte[] read(String ref){return keys.read(ref);}
	public void discardAfterCommit(String ref){if(ref==null)return;TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
		@Override public void afterCommit(){discard(ref);}
	});}
	private void discard(String ref){try{keys.discard(ref);}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(getClass()).error("Temporary key cleanup needs retry.");}}
}

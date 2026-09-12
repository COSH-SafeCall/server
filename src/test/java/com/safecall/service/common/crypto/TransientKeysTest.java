package com.safecall.service.common.crypto;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransientKeysTest {
	private final UserKeyStore store=mock(UserKeyStore.class);
	private final TransientKeys keys=new TransientKeys(store);
	@AfterEach void clear(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();}
	private TransactionSynchronization create(){
		TransactionSynchronizationManager.initSynchronization();
		when(store.create(any())).thenReturn("synthetic-key-ref");
		keys.create(UUID.randomUUID());
		return TransactionSynchronizationManager.getSynchronizations().getFirst();
	}
	@Test void rollbackDiscardsNewKey(){
		create().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
		verify(store).discard("synthetic-key-ref");
	}
	@Test void committedTransactionRetainsNewKey(){
		create().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
		verify(store,never()).discard(anyString());
	}
	@Test void unknownCommitRetainsKeyForOutcomeReconciliation(){
		create().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
		verify(store,never()).discard(anyString());
	}
	@Test void missingTransactionCannotCreateOrphanKey(){
		assertThatThrownBy(()->keys.create(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(store);
	}
	@Test void existingKeyIsDiscardedOnlyAfterSuccessfulCommit(){
		TransactionSynchronizationManager.initSynchronization();
		keys.discardAfterCommit("existing-key-ref");
		var synchronization=TransactionSynchronizationManager.getSynchronizations().getFirst();
		synchronization.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
		verifyNoInteractions(store);
		synchronization.afterCommit();
		verify(store).discard("existing-key-ref");
	}
}

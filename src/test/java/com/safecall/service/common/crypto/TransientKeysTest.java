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
	private final KeyDiscardQueue queue=mock(KeyDiscardQueue.class);
	private final TransientKeys keys=new TransientKeys(store,queue);
	private final UUID job=UUID.randomUUID();
	@AfterEach void clear(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
	private TransactionSynchronization create(){
		TransactionSynchronizationManager.initSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(true);
		when(store.reference(any())).thenReturn("synthetic-key-ref");when(queue.prepareCreation("synthetic-key-ref")).thenReturn(job);
		when(store.create(any())).thenReturn("synthetic-key-ref");
		keys.create(UUID.randomUUID());
		return TransactionSynchronizationManager.getSynchronizations().getFirst();
	}
	@Test void rollbackDiscardsNewKey(){
		create().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
		verify(queue).attempt(job);
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
	@Test void existingKeyRecordsIntentWithoutCallingExternalStoreOnCommit(){
		TransactionSynchronizationManager.initSynchronization();
		UUID job=UUID.randomUUID();when(queue.enqueue("existing-key-ref")).thenReturn(job);
		keys.discardAfterCommit("existing-key-ref");
		verify(queue).enqueue("existing-key-ref");
		assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
		verifyNoInteractions(store);
		verify(queue,never()).attempt(any());
	}
}

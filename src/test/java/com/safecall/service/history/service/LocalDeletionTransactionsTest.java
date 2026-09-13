package com.safecall.service.history.service;

import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.UserKeyStore;

class LocalDeletionTransactionsTest {
	@Test void uncertainCommitDoesNotReportFailedOrDiscardKeys() {
		var manager=mock(PlatformTransactionManager.class);var auth=mock(AuthRepository.class);var jdbc=mock(JdbcTemplate.class);var keys=mock(UserKeyStore.class);
		when(manager.getTransaction(any())).thenAnswer(inv->{TransactionSynchronizationManager.initSynchronization();return new SimpleTransactionStatus();});
		doAnswer(inv->{
			for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
			TransactionSynchronizationManager.clearSynchronization();throw new TransactionSystemException("synthetic uncertain commit");
		}).when(manager).commit(any());
		try {
			new LocalDeletionTransactions(manager,auth,jdbc,keys,mock(com.safecall.service.common.crypto.SecretCrypto.class),
				mock(com.safecall.service.common.crypto.TransientKeys.class)).execute(UUID.randomUUID(),UUID.randomUUID(),()->{});
			verifyNoInteractions(auth,jdbc,keys);verify(manager,never()).rollback(any());
		} finally {if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();}
	}
}

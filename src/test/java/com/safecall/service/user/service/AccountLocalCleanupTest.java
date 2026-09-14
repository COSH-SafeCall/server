package com.safecall.service.user.service;

import static org.mockito.Mockito.*;
import static com.safecall.service.auth.repository.AuthRepository.bin;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.*;
import com.safecall.service.history.service.LocalDeletionTransactions;
import com.safecall.service.user.repository.UserRepository;

class AccountLocalCleanupTest {
	private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
	private final AuthRepository auth=mock(AuthRepository.class);
	private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
	private final LocalDeletionTransactions deletions=mock(LocalDeletionTransactions.class);
	private final UUID first=UUID.randomUUID(),second=UUID.randomUUID(),job=UUID.randomUUID(),owner=UUID.randomUUID();
	private final AccountLocalCleanup cleanup=new AccountLocalCleanup(jdbc,auth,mock(UserRepository.class),mock(SecretCrypto.class),
		mock(UserKeyStore.class),mock(TransientKeys.class),JsonMapper.builder().build(),Clock.systemUTC(),manager,deletions);

	private void setup() {
		when(manager.getTransaction(any())).thenAnswer(inv->new SimpleTransactionStatus());
		when(jdbc.queryForList(contains("FROM `appUser`"),eq(byte[].class),any(Object.class)))
			.thenReturn(List.of(bin(first),bin(second)));
		when(jdbc.queryForList(contains("FROM `deletionJob`"),any(Object.class)))
			.thenReturn(List.of(Map.<String,Object>of("id",bin(job),"userId",bin(owner))));
	}

	@Test void failedAccountRollsBackAndStillProcessesOtherAccountsAndDeletionJobs() {
		setup();
		when(auth.user(first,true)).thenThrow(new CannotAcquireLockException("synthetic lock timeout"));
		cleanup.run();cleanup.run();
		verify(manager,times(2)).rollback(any());
		verify(manager,times(2)).commit(any());
		verify(auth,times(2)).user(second,true);
		verify(deletions,times(2)).execute(eq(job),eq(owner),any(Runnable.class));
	}

	@Test void uncertainCommitDoesNotBlockFollowingAccountsOrDeletionJobs() {
		setup();
		doThrow(new TransactionSystemException("synthetic uncertain commit")).doNothing().when(manager).commit(any());
		cleanup.run();
		verify(manager,never()).rollback(any());
		verify(auth).user(second,true);
		verify(deletions).execute(eq(job),eq(owner),any(Runnable.class));
	}

	@Test void failedAbandonedLookupStillProcessesDeletionJobs() {
		setup();
		when(jdbc.queryForList(contains("FROM `appUser`"),eq(byte[].class),any(Object.class)))
			.thenThrow(new CannotAcquireLockException("synthetic lookup failure"));
		cleanup.run();
		verify(deletions).execute(eq(job),eq(owner),any(Runnable.class));
	}
}

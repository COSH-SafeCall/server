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
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.*;
import com.safecall.service.history.service.LocalDeletionTransactions;
import com.safecall.service.user.repository.UserRepository;

class AccountLocalCleanupTest {
	private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
	private final AuthRepository auth=mock(AuthRepository.class);
	private final LocalDeletionTransactions deletions=mock(LocalDeletionTransactions.class);
	private final UUID job=UUID.randomUUID(),owner=UUID.randomUUID();
	private final AccountLocalCleanup cleanup=new AccountLocalCleanup(jdbc,auth,mock(UserRepository.class),mock(SecretCrypto.class),
		mock(TransientKeys.class),Clock.systemUTC(),deletions);

	@Test void onlyExplicitDeletionJobsAreScheduledWithoutScanningIncompleteAccounts() {
		when(jdbc.queryForList(contains("FROM `deletionJob`"),any(Object.class)))
			.thenReturn(List.of(Map.<String,Object>of("id",bin(job),"userId",bin(owner))));
		cleanup.run();
		verify(deletions).execute(eq(job),eq(owner),any(Runnable.class));
		verify(jdbc,never()).queryForList(contains("FROM `appUser`"),eq(byte[].class),any(Object.class));
	}
}

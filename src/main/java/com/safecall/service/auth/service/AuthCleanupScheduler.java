package com.safecall.service.auth.service;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.safecall.service.auth.repository.AuthRepository;

@Component
public class AuthCleanupScheduler {
	private final AuthMaintenance maintenance;
	private final AuthTransactions transactions;
	private final AuthRepository repository;
	private final Clock clock;
	public AuthCleanupScheduler(AuthMaintenance maintenance, AuthTransactions transactions, AuthRepository repository, Clock clock) {
		this.maintenance = maintenance; this.transactions = transactions; this.repository = repository; this.clock = clock;
	}
	@Scheduled(fixedDelayString = "${app.auth.cleanup-delay-ms}", initialDelayString = "${app.auth.cleanup-delay-ms}")
	public void run() {
		try {
			maintenance.cleanup();
			for (var sessionId : repository.expiredSessions(clock.instant())) transactions.expire(sessionId);
		} catch (RuntimeException exception) {
			// DB 복구 후 다음 주기에 재시도한다. SQL/예외 본문은 로그에 넣지 않는다.
			org.slf4j.LoggerFactory.getLogger(getClass()).error("Authentication cleanup failed; retrying next cycle.");
		}
	}
}

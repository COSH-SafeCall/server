package com.safecall.service.auth.service;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.*;

@Service
public class AuthMaintenance {
	private final AuthRepository repository;
	private final SecretCrypto crypto;
	private final Clock clock;
	private final int limit;
	public AuthMaintenance(AuthRepository repository, SecretCrypto crypto, Clock clock,
		@Value("${app.auth.requests-per-minute}") int limit) {
		if (limit <= 0) throw new IllegalStateException("Invalid authentication request limit.");
		this.repository = repository; this.crypto = crypto; this.clock = clock; this.limit = limit;
	}
	@Transactional(noRollbackFor = CustomException.class)
	public void checkRate(String remoteAddress) {
		Instant now = clock.instant();
		Instant window = Instant.ofEpochSecond(now.getEpochSecond() / 60 * 60);
		if (repository.countAttempt(crypto.hash("RATE_IP", remoteAddress), window, window.plusSeconds(120)) > limit) {
			throw new CustomException(ErrorCode.RATE_LIMITED, (int) (window.plusSeconds(60).getEpochSecond() - now.getEpochSecond()));
		}
	}
	@Transactional
	public void cleanup() { repository.purgeResponses(clock.instant()); }
}

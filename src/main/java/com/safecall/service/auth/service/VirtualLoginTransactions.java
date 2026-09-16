package com.safecall.service.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.common.crypto.TransientKeys;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class VirtualLoginTransactions {
	private final AuthTransactions authentication;
	private final AuthRepository repository;
	private final TransientKeys keys;
	private final WebPolicy policy;
	private final Clock clock;

	public VirtualLoginTransactions(AuthTransactions authentication, AuthRepository repository,
		TransientKeys keys, WebPolicy policy, Clock clock) {
		this.authentication = authentication;
		this.repository = repository;
		this.keys = keys;
		this.policy = policy;
		this.clock = clock;
	}

	public AuthTransactions.SessionResult login(String cookie) {
		var previous = authentication.basic(cookie, false);
		if (previous.userId() != null) {
			throw new CustomException(ErrorCode.ALREADY_AUTHENTICATED);
		}
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		UUID userId = UUID.randomUUID();
		String keyRef = keys.create(userId);
		repository.createUser(userId, keyRef, now);
		authentication.end(previous, "REVOKED", "LOGOUT");
		return authentication.issue(userId, "MEMBER", now.plusSeconds(policy.memberSeconds()));
	}
}

package com.safecall.service.telemetry.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.*;
import com.safecall.service.telemetry.api.TelemetryDtos.*;
import com.safecall.service.telemetry.repository.TelemetryRepository;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED,noRollbackFor=SessionInvalidException.class)
public class TelemetryService {
	private final AuthTransactions authentication;
	private final TelemetryRepository repository;
	private final TelemetryPolicy policy;
	private final SecretCrypto crypto;
	private final Clock clock;
	public TelemetryService(AuthTransactions authentication,TelemetryRepository repository,TelemetryPolicy policy,SecretCrypto crypto,Clock clock) {
		this.authentication=authentication;this.repository=repository;this.policy=policy;this.crypto=crypto;this.clock=clock;
	}
	public EventCounts accept(String cookie,EventBatch batch) {
		// 인증의 계정→세션 잠금을 유지해 동시 배치·삭제와 중복 판정/한도 차감을 직렬화한다.
		var session=authentication.authenticated(cookie);
		Map<UUID,TelemetryEvent> unique=new LinkedHashMap<>();
		for(var input:batch.events()) {
			var event=policy.validate(input);var previous=unique.putIfAbsent(event.eventId(),event);
			if(previous!=null && !previous.equals(event)) throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		List<TelemetryEvent> accepted=new ArrayList<>();
		for(var event:unique.values()) {
			if(event.callId()!=null && !repository.ownsCall(session.id(),event.callId())) throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
			var previous=repository.find(session.id(),event.eventId());
			if(previous==null) accepted.add(event);
			else if(!previous.equals(event)) throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		if(!accepted.isEmpty()) {
			var now=clock.instant();var window=now.truncatedTo(ChronoUnit.MINUTES);
			if(repository.charge(crypto.hash("TELEMETRY_RATE",session.id().toString()),window,accepted.size())>120)
				throw new CustomException(ErrorCode.RATE_LIMITED,Math.max(1,60-(int)Duration.between(window,now).getSeconds()));
			String version=policy.webVersion();
			for(var event:accepted) repository.insert(session.id(),event,version,now);
		}
		return new EventCounts(accepted.size(),batch.events().size()-accepted.size());
	}
}

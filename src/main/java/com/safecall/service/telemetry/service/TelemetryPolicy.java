package com.safecall.service.telemetry.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.safecall.service.common.error.*;
import com.safecall.service.telemetry.api.TelemetryDtos.TelemetryEvent;

@Component
public class TelemetryPolicy {
	private static final Map<String,Set<String>> CODES=Map.of(
		"PERMISSION",Set.of("MICROPHONE_PERMISSION_REVIEWED","LOCATION_PERMISSION_REVIEWED","PERMISSION_QUERY_UNAVAILABLE"),
		"CALL",Set.of("LIVE_CONNECT_STARTED","LIVE_CONNECT_SUCCEEDED","LIVE_CONNECT_FAILED","RINGING_SHOWN","RINGING_FAILED","PAGE_EXITED","PAGE_RELOADED","LIVE_RESUME_STARTED","LIVE_RESUME_SUCCEEDED","LIVE_RESUME_FAILED"),
		"AUDIO",Set.of("FIRST_AUDIO_PLAYED","AUDIO_INTERRUPTED"),
		"GESTURE",Set.of("QUICK_START_SELECTED","QUICK_START_CANCELLED"),
		"LOCATION",Set.of("LOCATION_AVAILABLE","LOCATION_UNAVAILABLE"),
		"MESSAGE_COMPOSER",Set.of("COMPOSER_OPENED","COMPOSER_OPEN_FAILED"),
		"SOS",Set.of("SOS_GUIDE_VIEWED","SOS_GUIDE_FAILED"),
		"FALLBACK",Set.of("FALLBACK_STARTED","FALLBACK_ENDED"));
	private static final Set<String> SUCCESSES=Set.of("LIVE_CONNECT_SUCCEEDED","RINGING_SHOWN","LIVE_RESUME_SUCCEEDED",
		"FIRST_AUDIO_PLAYED","LOCATION_AVAILABLE","COMPOSER_OPENED","SOS_GUIDE_VIEWED");
	private static final Set<String> FAILURES=Set.of("PERMISSION_QUERY_UNAVAILABLE","LIVE_CONNECT_FAILED","RINGING_FAILED",
		"LIVE_RESUME_FAILED","LOCATION_UNAVAILABLE","COMPOSER_OPEN_FAILED","SOS_GUIDE_FAILED");
	private final String webVersion;
	public TelemetryPolicy(@Value("${app.telemetry.web-version}") String webVersion) {
		if(webVersion==null || !webVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,39}"))
			throw new IllegalArgumentException("Telemetry web version must be a release identifier of 1 to 40 characters.");
		this.webVersion=webVersion;
	}
	public String webVersion() { return webVersion; }
	public TelemetryEvent validate(TelemetryEvent event) {
		if(!CODES.getOrDefault(event.category(),Set.of()).contains(event.code())
			|| event.networkType()!=null && !Set.of("WIFI","CELLULAR","OFFLINE","UNKNOWN").contains(event.networkType())
			|| Boolean.FALSE.equals(event.isSuccess()) && SUCCESSES.contains(event.code())
			|| Boolean.TRUE.equals(event.isSuccess()) && FAILURES.contains(event.code()))
			throw new CustomException(ErrorCode.INVALID_EVENT);
		if(event.occurredAt().isBefore(Instant.parse("1000-01-01T00:00:00Z"))
			|| !event.occurredAt().isBefore(Instant.parse("9999-12-31T23:59:59.999999Z").plusNanos(1000)))
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		// DB와 API의 UTC 마이크로초 정밀도로 비교해 시간대 표기·재전송 차이를 제거한다.
		return new TelemetryEvent(event.eventId(),event.callId(),event.category(),event.code(),event.isSuccess(),
			event.latencyMs(),event.networkType(),event.occurredAt().truncatedTo(ChronoUnit.MICROS));
	}
}

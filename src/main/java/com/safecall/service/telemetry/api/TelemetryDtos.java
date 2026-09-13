package com.safecall.service.telemetry.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class TelemetryDtos {
	private TelemetryDtos() {}
	public record EventBatch(@NotNull @Size(min=1,max=20) List<@NotNull @Valid TelemetryEvent> events) {
		@Override public String toString() { return "EventBatch[redacted]"; }
	}
	public record TelemetryEvent(@NotNull UUID eventId, UUID callId,
		@NotBlank @Size(max=16) String category, @NotBlank @Size(max=48) String code,
		Boolean isSuccess, @Min(0) @Max(3600000) Integer latencyMs,
		@Size(max=8) String networkType,
		@NotNull @tools.jackson.databind.annotation.JsonDeserialize(using=EventTimeDeserializer.class) Instant occurredAt) {
		@Override public String toString() { return "TelemetryEvent[redacted]"; }
	}
	public record EventCounts(int acceptedCount,int duplicateCount) {}
}

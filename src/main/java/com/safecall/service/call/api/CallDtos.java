package com.safecall.service.call.api;
import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.safecall.service.common.api.DatabaseInstantDeserializer;
import com.safecall.service.auth.api.AuthDtos.Permission;
public final class CallDtos {
	private CallDtos() {}
	public enum StartMode { STANDARD, QUICK }
	public enum EventType { CONNECTED, RINGING_SHOWN, ANSWERED, FAILED }
	public enum Failure { CONNECTION_FAILED, RINGING_FAILED, MICROPHONE_FAILED, AUDIO_FAILED, CONNECTION_LOST }
	public enum EndReason { USER_ENDED, DECLINED, BACK_NAVIGATION, TAB_HIDDEN, PAGE_EXIT, PAGE_RELOAD, SWITCH_TO_FALLBACK }
	public record CreateCall(@NotNull UUID clientCallId,@NotNull StartMode startMode,@NotBlank @Size(max=24) String scenarioCode,
		@NotBlank @Size(max=6) String counterpartCode,@NotNull Permission microphonePermission) {}
	public record CallEvent(@NotNull UUID eventId,@NotNull EventType type,UUID grantId,@NotNull @JsonDeserialize(using=DatabaseInstantDeserializer.class) Instant occurredAt,
		@NotNull @Positive Long expectedVersion,Failure errorCode) {}
	public record EndCall(@NotNull EndReason reason,@NotNull @JsonDeserialize(using=DatabaseInstantDeserializer.class) Instant occurredAt) {}
	public record HeartbeatRequest() {}
	public record HeartbeatView(String state,Instant leaseExpiresAt,Instant expiresAt) {}
	public record CallView(UUID id,UUID clientCallId,String state,String startMode,String scenarioCode,String counterpartCode,String displayName,
		Instant createdAt,Instant ringingAt,Instant answeredAt,Instant endedAt,String endReason,Instant leaseExpiresAt,Instant expiresAt,
		String policyVersion,long version) {}
	public record IssuingView(String status,int retryAfterMs) {}
	public record ConnectionView(UUID grantId,String status,String token,String model,String apiVersion,String voiceId,
		List<String> responseModalities,Instant newSessionExpiresAt,Instant expiresAt,int uses) {
		@Override public String toString(){return "ConnectionView[redacted]";}
	}
}

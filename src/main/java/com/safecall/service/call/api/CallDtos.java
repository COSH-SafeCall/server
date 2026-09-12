package com.safecall.service.call.api;
import java.time.Instant;
import java.util.*;
import jakarta.validation.constraints.*;
import com.safecall.service.auth.api.AuthDtos.Permission;
public final class CallDtos {
	private CallDtos() {}
	public enum StartMode { STANDARD, QUICK }
	public enum EventType { CONNECTED, RINGING_SHOWN, ANSWERED, CONNECTION_INTERRUPTED, GO_AWAY, RESUMED, FAILED }
	public enum Failure { CONNECTION_FAILED, RINGING_FAILED, MICROPHONE_FAILED, AUDIO_FAILED, CONNECTION_LOST, RESUMPTION_FAILED }
	public enum EndReason { USER_ENDED, DECLINED, BACK_NAVIGATION, TAB_HIDDEN, PAGE_EXIT, PAGE_RELOAD, SWITCH_TO_FALLBACK }
	public enum RenewalReason { GO_AWAY, CONNECTION_INTERRUPTED }
	public record CreateCall(@NotNull UUID clientCallId,@NotNull StartMode startMode,@NotBlank @Size(max=24) String scenarioCode,
		@NotBlank @Size(max=6) String counterpartCode,@NotNull Permission microphonePermission) {}
	public record CallEvent(@NotNull UUID eventId,@NotNull EventType type,UUID grantId,@NotNull Instant occurredAt,
		@NotNull @Positive Long expectedVersion,Failure errorCode) {}
	public record EndCall(@NotNull EndReason reason,@NotNull Instant occurredAt) {}
	public record RenewalRequest(@NotNull UUID previousGrantId,@NotNull RenewalReason reason,@NotNull @AssertTrue Boolean isResumable) {}
	public record GrantView(UUID grantId,int generation,String purpose,String status) {}
	public record HeartbeatRequest() {}
	public record HeartbeatView(String state,Instant leaseExpiresAt,Instant expiresAt) {}
	public record CallView(UUID id,UUID clientCallId,String state,String startMode,String scenarioCode,String counterpartCode,String displayName,
		Instant createdAt,Instant ringingAt,Instant answeredAt,Instant endedAt,String endReason,Instant leaseExpiresAt,Instant expiresAt,
		String policyVersion,int maxResumeAttempts,int resumeDelayMs,long version) {}
	public record IssuingView(String status,int retryAfterMs) {}
	public record SessionResumption(boolean isEnabled) {}
	public record ConnectionView(UUID grantId,int generation,String purpose,String status,String token,String model,String apiVersion,String voiceId,
		List<String> responseModalities,SessionResumption sessionResumption,Instant newSessionExpiresAt,Instant expiresAt,int uses) {
		@Override public String toString(){return "ConnectionView[redacted]";}
	}
}

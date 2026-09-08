package com.safecall.service.auth.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class AuthDtos {
	private AuthDtos() {}
	public enum Platform { ANDROID }
	public enum Step { PROFILE, CONTACTS, CONSENTS, PERMISSIONS, SOS_GUIDE, MESSAGE_TEST, COMPLETE }
	public enum Permission { GRANTED, DENIED, NOT_DETERMINED }
	public enum TestDecision { SKIP, FINISH }

	public record GuestRequest(@NotNull UUID installationId, @NotNull Platform platform,
		@NotBlank @Size(max=40) String appVersion, @NotBlank @Size(max=40) String osVersion,
		@NotBlank @Size(min=43, max=256) String bootstrapSecret,
		@Size(max=2048) String currentRefreshToken) {
		@Override public String toString() { return "GuestRequest[redacted]"; }
	}
	public record KakaoRequest(@NotNull UUID installationId, @NotNull Platform platform,
		@NotBlank @Size(max=40) String appVersion, @NotBlank @Size(max=40) String osVersion,
		@NotBlank @Size(max=2048) String kakaoAccessToken, @Size(max=2048) String currentRefreshToken) {
		@Override public String toString() { return "KakaoRequest[redacted]"; }
	}
	public record RefreshRequest(@NotBlank @Size(max=2048) String refreshToken) {
		@Override public String toString() { return "RefreshRequest[redacted]"; }
	}
	public record EmptyRequest() {}
	public record PermissionReview(@NotNull Permission microphone, Permission location) {}
	public record AdvanceRequest(@NotNull Step step, @NotNull @Positive Long expectedVersion,
		@Valid PermissionReview permissionReview, TestDecision testDecision) {}
	public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken,
		Instant refreshExpiresAt, UUID sessionId) {
		@Override public String toString() { return "Tokens[redacted]"; }
	}
	public record SessionView(UUID sessionId, String kind, UUID userId, Step onboardingStep,
		Instant expiresAt, boolean isReconsentRequired) {}
	public record ProfileView(UUID userId, String name, String gender, LocalDate birthDate,
		String phone, String genderSource, String birthDateSource, Instant confirmedAt, long version) {
		@Override public String toString() { return "ProfileView[redacted]"; }
	}
	public record AuthResponse(Tokens tokens, SessionView session, ProfileView profile) {
		@Override public String toString() { return "AuthResponse[redacted]"; }
	}
	public record OnboardingView(Step step, List<String> requiredMissingFields,
		List<String> requiredConsentCodes, boolean isAdvanceAllowed, long version) {}
}

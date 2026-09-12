package com.safecall.service.auth.api;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.safecall.service.user.api.UserDtos.Decision;
public final class AuthDtos {
	private AuthDtos() {}
	public enum Step { ENTRY, PROFILE, CONTACTS, CONSENTS, PERMISSIONS, SOS_GUIDE, MESSAGE_TEST, COMPLETE }
	public enum Permission { GRANTED, DENIED, NOT_DETERMINED }
	public enum TestDecision { SKIP, COMPLETED }
	public enum Purpose { LOGIN, REAUTH }
	public record EmptyRequest() {}
	public record AuthorizationRequest(@NotNull Purpose purpose, @Size(min=3,max=3) List<@NotNull @Valid Decision> decisions) {}
	public record AuthorizationView(String authorizationUrl, Instant expiresAt) {
		@Override public String toString() { return "AuthorizationView[redacted]"; }
	}
	public record AdvanceRequest(@NotNull Step step, @NotNull @Positive Long expectedVersion,
		Boolean isNoticeReviewed, TestDecision testDecision) {}
	public record SessionView(String kind, boolean isAuthenticated, String csrfToken, Instant expiresAt,
		Step onboardingStep, String settingsMode) {
		@Override public String toString() { return "SessionView[redacted]"; }
	}
	public record ProfileView(String name, String gender, LocalDate birthDate, String phone,
		String genderSource, String birthDateSource, List<String> missingFields, Instant profileConfirmedAt, long version) {
		@Override public String toString() { return "ProfileView[redacted]"; }
	}
	public record OnboardingView(Step step, long version) {}
}

package com.safecall.service.auth.api;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.safecall.service.user.api.UserDtos.Decision;
public final class AuthDtos {
	private AuthDtos() {}
	public enum Permission { GRANTED, DENIED, NOT_DETERMINED }
	public enum Purpose { LOGIN, REAUTH }
	public record EmptyRequest() {}
	public record AuthorizationRequest(@NotNull Purpose purpose) {}
	public record AuthorizationView(String authorizationUrl, Instant expiresAt) {
		@Override public String toString() { return "AuthorizationView[redacted]"; }
	}
	public record ProfilePrefill(String name, String gender, LocalDate birthDate, String phone) {
		@Override public String toString() { return "ProfilePrefill[redacted]"; }
	}
	public record SessionView(String kind, boolean isAuthenticated, String csrfToken, Instant expiresAt,
		String settingsMode, ProfilePrefill profilePrefill) {
		public SessionView withProfilePrefill(ProfilePrefill value) {
			return new SessionView(kind,isAuthenticated,csrfToken,expiresAt,settingsMode,value);
		}
		@Override public String toString() { return "SessionView[redacted]"; }
	}
	public record ProfileView(String name, String gender, LocalDate birthDate, String phone,
		String genderSource, String birthDateSource, List<String> missingFields, Instant profileConfirmedAt, long version) {
		@Override public String toString() { return "ProfileView[redacted]"; }
	}
}

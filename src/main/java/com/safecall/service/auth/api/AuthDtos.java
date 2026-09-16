package com.safecall.service.auth.api;
import java.time.*;
import java.util.*;
public final class AuthDtos {
	private AuthDtos() {}
	public enum Permission { GRANTED, DENIED, NOT_DETERMINED }
	public record EmptyRequest() {}
	public record SessionView(String kind, boolean isAuthenticated, String csrfToken, Instant expiresAt,
		String settingsMode) {
		@Override public String toString() { return "SessionView[redacted]"; }
	}
	public record ProfileView(String name, String gender, LocalDate birthDate, String phone,
		String genderSource, String birthDateSource, List<String> missingFields, Instant profileConfirmedAt, long version) {
		@Override public String toString() { return "ProfileView[redacted]"; }
	}
}

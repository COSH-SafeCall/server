package com.safecall.service.user.api;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class UserDtos {
	private UserDtos() {}
	public enum Gender { MALE, FEMALE, UNKNOWN }
	public enum AlertMode { RINGTONE, VIBRATE, SILENT }
	public enum DecisionAction { GRANTED, DECLINED }
	public record ProfileRequest(@NotBlank @Size(max=200) String name, @NotNull Gender gender,
		@JsonProperty(required=true) @Size(max=10) String birthDate,
		@NotBlank @Size(max=32) String phone, @NotNull @Positive Long expectedVersion) {
		@Override public String toString() { return "ProfileRequest[redacted]"; }
	}
	public record ContactRequest(@NotBlank @Size(max=200) String name,
		@NotBlank @Size(max=120) String relationship, @NotBlank @Size(max=32) String phone) {
		@Override public String toString() { return "ContactRequest[redacted]"; }
	}
	public record ContactUpdate(@NotBlank @Size(max=200) String name,
		@NotBlank @Size(max=120) String relationship, @NotBlank @Size(max=32) String phone,
		@NotNull @Positive Long expectedVersion) {
		@Override public String toString() { return "ContactUpdate[redacted]"; }
	}
	public record ContactView(UUID id, int slot, String name, String relationship, String phone, long version) {
		@Override public String toString() { return "ContactView[redacted]"; }
	}
	public record Items<T>(List<T> items) {
		@Override public String toString() { return "Items[redacted]"; }
	}
	public record SettingRequest(@NotNull AlertMode incomingAlertMode, @NotNull @Positive Long expectedVersion) {}
	public record SettingView(AlertMode incomingAlertMode, long version) {}
	public record DocumentView(String code, int version, String title, String body, String purpose,
		boolean isConsent, boolean isRequired, Instant publishedAt) {}
	public record Decision(@NotBlank @Size(max=24) String code, @NotNull @Positive Integer version,
		@NotNull DecisionAction action) {}
	public record DecisionsRequest(@NotNull @Size(min=1,max=3) List<@NotNull @Valid Decision> decisions) {}
	public record ConsentView(String code, int version, String action, Integer decisionVersion,
		String purpose, Instant recordedAt, boolean isValid) {}
	public record WithdrawRequest(@NotNull @Positive Integer version) {}
	public record DeletionReceipt(UUID id, String scope, String status, String receiptToken, Instant dueAt, Instant receiptExpiresAt) {
		@Override public String toString() { return "DeletionReceipt[redacted]"; }
	}
	public record WithdrawalView(String code, String action, DeletionReceipt deletion) {
		@Override public String toString() { return "WithdrawalView[redacted]"; }
	}
}

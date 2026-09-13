package com.safecall.service.message.api;

import java.time.Instant;
import java.util.List;
import com.safecall.service.user.api.UserDtos.ContactView;

public final class MessageDtos {
	private MessageDtos() {}
	public enum Mode { SAFETY, TEST }
	public record Identity(String name, String maskedPhone) {
		@Override public String toString() { return "Identity[redacted]"; }
	}
	public record MapTemplate(int version, String urlTemplate, String coordinateSystem,
		int maxAgeSeconds, int maxAccuracyMeters) {}
	public record MessageComposerView(Mode mode, List<ContactView> recipients, Identity identity,
		String baseBody, int templateVersion, boolean isLocationConsentGranted, MapTemplate mapTemplate,
		String notice, Instant preparedAt, Instant expiresAt) {
		@Override public String toString() { return "MessageComposerView[redacted]"; }
	}
}

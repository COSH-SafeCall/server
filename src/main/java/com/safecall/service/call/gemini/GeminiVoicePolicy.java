package com.safecall.service.call.gemini;

import org.springframework.stereotype.Component;

@Component
public class GeminiVoicePolicy {
	static final String FATHER_VOICE = "Orus";
	static final String FRIEND_VOICE = "Zubenelgenubi";

	public String select(String counterpartCode, String reviewedVoiceId) {
		return switch (counterpartCode) {
			case "FATHER" -> FATHER_VOICE;
			case "FRIEND" -> FRIEND_VOICE;
			default -> reviewedVoiceId;
		};
	}
}

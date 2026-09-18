package com.safecall.service.call.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeminiVoicePolicyTest {
	private final GeminiVoicePolicy policy = new GeminiVoicePolicy();

	@Test void fatherUsesFirmMaleVoice() {
		assertThat(policy.select("FATHER", "Puck")).isEqualTo("Alnilam");
	}

	@Test void friendUsesCasualMaleVoice() {
		assertThat(policy.select("FRIEND", "Puck")).isEqualTo("Zubenelgenubi");
	}

	@Test void motherKeepsReviewedReleaseVoice() {
		assertThat(policy.select("MOTHER", "Aoede")).isEqualTo("Aoede");
	}
}

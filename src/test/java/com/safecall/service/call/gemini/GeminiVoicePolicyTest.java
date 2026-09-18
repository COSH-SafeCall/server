package com.safecall.service.call.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeminiVoicePolicyTest {
	private final GeminiVoicePolicy policy = new GeminiVoicePolicy();

	@Test void fatherUsesSmoothMaleVoice() {
		assertThat(policy.select("FATHER", "Puck")).isEqualTo("Algieba");
	}

	@Test void friendUsesInformativeMaleVoice() {
		assertThat(policy.select("FRIEND", "Puck")).isEqualTo("Rasalgethi");
	}

	@Test void motherKeepsReviewedReleaseVoice() {
		assertThat(policy.select("MOTHER", "Aoede")).isEqualTo("Aoede");
	}
}

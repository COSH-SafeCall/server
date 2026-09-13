package com.safecall.service.call.gemini;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HttpGeminiClientTest {
	private static final String URL="https://generativelanguage.googleapis.com/v1beta/auth_tokens";
	private final RestClient.Builder builder=RestClient.builder();
	private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
	private final HttpGeminiClient client=new HttpGeminiClient(builder.build(),JsonMapper.builder().build(),"synthetic-key",URL);
	private GeminiClient.IssueRequest request() {
		return new GeminiClient.IssueRequest("models/gemini-3.1-flash-live-preview","v1beta","Puck","synthetic system policy",
			Instant.parse("2026-09-10T00:01:00Z"),Instant.parse("2026-09-10T00:09:00Z"));
	}
	@Test void locksModelVoiceInstructionsAndAllowsBrowserResumption() {
		server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andExpect(header("x-goog-api-key","synthetic-key"))
			.andExpect(jsonPath("$.uses").value(1)).andExpect(jsonPath("$.expireTime").value("2026-09-10T00:09:00Z"))
			.andExpect(jsonPath("$.newSessionExpireTime").value("2026-09-10T00:01:00Z"))
			.andExpect(jsonPath("$.bidiGenerateContentSetup.model").value(request().model()))
			.andExpect(jsonPath("$.bidiGenerateContentSetup.systemInstruction.parts[0].text").value("synthetic system policy"))
			.andExpect(jsonPath("$.bidiGenerateContentSetup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName").value("Puck"))
			.andExpect(jsonPath("$.bidiGenerateContentSetup.generationConfig.responseModalities[0]").value("AUDIO"))
			.andExpect(jsonPath("$.bidiGenerateContentSetup.tools").doesNotExist())
			.andExpect(jsonPath("$.bidiGenerateContentSetup.sessionResumption").isMap())
			.andExpect(jsonPath("$.fieldMask").value("model,generationConfig,systemInstruction,sessionResumption,tools,contextWindowCompression,inputAudioTranscription,outputAudioTranscription"))
			.andRespond(withSuccess("{\"name\":\"auth_tokens/synthetic-token\"}",MediaType.APPLICATION_JSON));
		assertThat(client.issue(request())).isEqualTo("auth_tokens/synthetic-token"); server.verify();
	}
	@Test void missingKeyMakesNoRequest() {
		var blank=new HttpGeminiClient(builder.build(),JsonMapper.builder().build(),"",URL);
		assertThat(blank.isConfigured()).isFalse();
		assertThatThrownBy(()->blank.issue(request())).isInstanceOf(GeminiClient.IssueException.class); server.verify();
	}
	@Test void refusesUntrustedEndpoints() {
		for(String url:new String[]{"http://generativelanguage.googleapis.com/v1beta/auth_tokens","https://example.test/auth_tokens",URL+"?key=x"})
			assertThatThrownBy(()->new HttpGeminiClient(builder.build(),JsonMapper.builder().build(),"synthetic",url)).isInstanceOf(IllegalStateException.class);
	}
	@Test void providerErrorsAreSanitizedAndNotRetried() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("secret upstream diagnostics"));
		assertThatThrownBy(()->client.issue(request())).isInstanceOfSatisfying(GeminiClient.IssueException.class,e->{
			assertThat(e.isUnknown()).isFalse(); assertThat(e.getMessage()).doesNotContain("secret"); assertThat(e.getCause()).isNull();
		}); server.verify();
	}
	@Test void providerServerFailureIsUncertain() {
		server.expect(requestTo(URL)).andRespond(withServerError());
		assertUnknown(); server.verify();
	}
	@Test void malformedSuccessIsUncertain() {
		server.expect(requestTo(URL)).andRespond(withSuccess("not json",MediaType.TEXT_PLAIN)); assertUnknown(); server.verify();
	}
	@Test void emptyTokenIsUncertain() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"name\":\"\"}",MediaType.APPLICATION_JSON)); assertUnknown(); server.verify();
	}
	@Test void oversizedSuccessIsUncertain() {
		server.expect(requestTo(URL)).andRespond(withSuccess(" ".repeat(65537),MediaType.APPLICATION_JSON)); assertUnknown(); server.verify();
	}
	private void assertUnknown() { assertThatThrownBy(()->client.issue(request())).isInstanceOfSatisfying(GeminiClient.IssueException.class,e->assertThat(e.isUnknown()).isTrue()); }
	@Test void lifetimeConfigurationRejectsReferenceThirtyMinuteDefault() {
		assertThat(new GeminiSettings(540,60).connectionSeconds()).isEqualTo(540);
		for(int[] values:new int[][]{{1800,60},{540,61},{0,60},{20,60}})
			assertThatThrownBy(()->new GeminiSettings(values[0],values[1])).isInstanceOf(IllegalStateException.class);
	}
}

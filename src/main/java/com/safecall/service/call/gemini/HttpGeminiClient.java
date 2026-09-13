package com.safecall.service.call.gemini;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpGeminiClient implements GeminiClient {
	private final RestClient client;
	private final JsonMapper mapper;
	private final String apiKey;
	private final URI endpoint;
	@Autowired
	public HttpGeminiClient(JsonMapper mapper, @Value("${app.gemini.api-key}") String apiKey,
		@Value("${app.gemini.token-url}") String tokenUrl) {
		this(buildClient(),mapper,apiKey,tokenUrl);
	}
	HttpGeminiClient(RestClient client, JsonMapper mapper, String apiKey, String tokenUrl) {
		this.client=client; this.mapper=mapper; this.apiKey=apiKey;
		// The server secret may only be sent to the reviewed Google provisioning endpoint.
		if (!"https://generativelanguage.googleapis.com/v1beta/auth_tokens".equals(tokenUrl))
			throw new IllegalStateException("Unsupported Gemini token endpoint.");
		endpoint=URI.create(tokenUrl);
	}
	private static RestClient buildClient() {
		var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
		var factory=new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(5));
		return RestClient.builder().requestFactory(factory).build();
	}
	@Override public boolean isConfigured() { return apiKey!=null && !apiKey.isBlank(); }
	@Override public String issue(IssueRequest request) {
		if (!isConfigured()) throw new IssueException(false);
		if (!"v1beta".equals(request.apiVersion())) throw new IssueException(false);
		// Match the provider SDK wire format: protect model/voice/instructions and disabled tools,
		// while allowing the browser-only sessionResumption.handle on connection replacement.
		var setup=new java.util.LinkedHashMap<String,Object>();
		setup.put("model",request.model());
		setup.put("generationConfig",Map.of("responseModalities",List.of("AUDIO"),"speechConfig",Map.of("voiceConfig",Map.of("prebuiltVoiceConfig",Map.of("voiceName",request.voiceId())))));
		setup.put("sessionResumption",Map.of());
		if(request.instruction()!=null)setup.put("systemInstruction",Map.of("parts",List.of(Map.of("text",request.instruction()))));
		String mask="model,generationConfig,systemInstruction,sessionResumption,tools,contextWindowCompression,inputAudioTranscription,outputAudioTranscription";
		var body=Map.of("uses",1,"expireTime",request.expiresAt().toString(),"newSessionExpireTime",request.newSessionExpiresAt().toString(),"bidiGenerateContentSetup",setup,"fieldMask",mask);
		try {
			return client.post().uri(endpoint).header("x-goog-api-key",apiKey).contentType(MediaType.APPLICATION_JSON)
				.body(body).exchange((httpRequest,response)-> {
					if (!response.getStatusCode().is2xxSuccessful()) throw new IssueException(response.getStatusCode().is5xxServerError());
					byte[] bytes=response.getBody().readNBytes(65537);
					if (bytes.length>65536) throw new IssueException(true);
					var root=mapper.readTree(bytes);
					var name=root==null ? null : root.get("name");
					if (name==null || !name.isString() || !name.asString().matches("auth_tokens/[A-Za-z0-9._~+/=-]{1,8192}"))
						throw new IssueException(true);
					return name.asString();
				});
		} catch (IssueException exception) { throw exception; }
		catch (RuntimeException exception) { throw new IssueException(true); }
	}
}

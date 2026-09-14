package com.safecall.service.auth.kakao;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;

@Component
public class HttpKakaoClient implements KakaoClient {
	private final HttpClient client;
	private final JsonMapper mapper;
	private final Clock clock;
	private final long appId;

	@org.springframework.beans.factory.annotation.Autowired
	public HttpKakaoClient(JsonMapper mapper, Clock clock, @Value("${app.oauth.kakao.app-id}") long appId) {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER).build(), mapper, clock, appId);
	}
	HttpKakaoClient(HttpClient client, JsonMapper mapper, Clock clock, long appId) {
		this.client = client; this.mapper = mapper; this.clock = clock; this.appId = appId;
	}
	@Override
	public KakaoIdentity verify(String accessToken) {
		if (appId <= 0) throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
		JsonNode info = get("/v1/user/access_token_info", accessToken);
		if (!info.path("app_id").isIntegralNumber() || info.path("app_id").asLong() != appId
			|| !info.path("id").isIntegralNumber() || info.path("id").asLong() <= 0
			|| info.path("expires_in").asLong() <= 0) throw new CustomException(ErrorCode.KAKAO_TOKEN_INVALID);
		JsonNode user = get("/v2/user/me", accessToken);
		if (!user.path("id").isIntegralNumber() || user.path("id").asLong() != info.path("id").asLong()) {
			throw new CustomException(ErrorCode.KAKAO_TOKEN_INVALID);
		}
		return new KakaoIdentity(info.path("id").asString(), null, null, null, null);
	}

	private JsonNode get(String path, String accessToken) {
		try {
			var request = HttpRequest.newBuilder(URI.create("https://kapi.kakao.com" + path))
				.timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/json").GET().build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401 || response.statusCode() == 400 || response.statusCode() == 403) {
				throw new CustomException(ErrorCode.KAKAO_TOKEN_INVALID);
			}
			if (response.statusCode() != 200) throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
			return mapper.readTree(response.body());
		} catch (CustomException exception) { throw exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
		} catch (Exception exception) { throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE); }
	}
}

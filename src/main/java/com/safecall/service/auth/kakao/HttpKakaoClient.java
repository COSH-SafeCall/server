package com.safecall.service.auth.kakao;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.text.Normalizer;
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
		JsonNode account = user.path("kakao_account");
		String gender = switch (account.path("gender").asString("")) {
			case "male" -> "MALE";
			case "female" -> "FEMALE";
			default -> null;
		};
		if (account.path("gender_needs_agreement").asBoolean(false)) gender = null;
		String name = safeName(account.path("name").asString(null));
		if (account.path("name_needs_agreement").asBoolean(false)) name = null;
		String phone = phone(account.path("phone_number").asString(null));
		if (account.path("phone_number_needs_agreement").asBoolean(false)) phone = null;
		return new KakaoIdentity(info.path("id").asString(), name, gender, birthDate(account), phone);
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
	private LocalDate birthDate(JsonNode account) {
		if (!"SOLAR".equals(account.path("birthday_type").asString())
			|| account.path("birthyear_needs_agreement").asBoolean(false)
			|| account.path("birthday_needs_agreement").asBoolean(false)) return null;
		try {
			String year = account.path("birthyear").asString("");
			String day = account.path("birthday").asString("");
			if (!year.matches("[0-9]{4}") || !day.matches("[0-9]{4}")) return null;
			LocalDate date = LocalDate.of(Integer.parseInt(year), Integer.parseInt(day.substring(0, 2)),
				Integer.parseInt(day.substring(2)));
			return date.isAfter(LocalDate.now(clock)) ? null : date;
		} catch (RuntimeException exception) { return null; }
	}
	private String safeName(String name) {
		if (name == null) return null;
		String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
		return normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 50
			|| normalized.codePoints().anyMatch(Character::isISOControl) ? null : normalized;
	}
	private String phone(String phone) {
		if (phone == null) return null;
		String normalized = phone.replace(" ", "").replace("-", "");
		if (normalized.startsWith("+82")) normalized = "0" + normalized.substring(3);
		return normalized.matches("010[0-9]{8}") ? normalized : null;
	}
}

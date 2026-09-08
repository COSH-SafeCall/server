package com.safecall.service.auth.kakao;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.common.error.*;

@SuppressWarnings("unchecked")
class HttpKakaoClientTest {
	private final HttpClient http = mock(HttpClient.class);
	private final HttpKakaoClient client = new HttpKakaoClient(http, JsonMapper.builder().build(),
		Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC), 123);
	private HttpResponse<String> response(int status, String body) {
		return new HttpResponse<>() {
			@Override public int statusCode() { return status; }
			@Override public String body() { return body; }
			@Override public HttpRequest request() { return null; }
			@Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
			@Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a,b) -> true); }
			@Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
			@Override public java.net.URI uri() { return java.net.URI.create("https://kapi.kakao.com"); }
			@Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
		};
	}
	@Test void verifiesAppSubjectAndNormalizesOnlyConfirmedSolarData() throws Exception {
		when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
			response(200, "{\"id\":456,\"app_id\":123,\"expires_in\":100}"),
			response(200, "{\"id\":456,\"kakao_account\":{\"name\":\" 홍길동 \",\"gender\":\"male\",\"birthday_type\":\"SOLAR\",\"birthyear\":\"2000\",\"birthday\":\"0101\",\"phone_number\":\"+82 10-1234-5678\"}}"));
		var identity = client.verify("synthetic-provider-token");
		assertThat(identity.subject()).isEqualTo("456");
		assertThat(identity.name()).isEqualTo("홍길동");
		assertThat(identity.phone()).isEqualTo("01012345678");
		assertThat(identity.birthDate()).isEqualTo(LocalDate.of(2000,1,1));
	}
	@Test void wrongAppAndExpiredProviderTokenAreRejected() throws Exception {
		for (String info : java.util.List.of("{\"id\":456,\"app_id\":999,\"expires_in\":100}", "{\"id\":456,\"app_id\":123,\"expires_in\":0}")) {
			when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200, info));
			assertThatThrownBy(() -> client.verify("synthetic")).isInstanceOfSatisfying(CustomException.class,
				e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_INVALID));
		}
	}
	@Test void subjectMismatchAndProviderOutageAreDistinct() throws Exception {
		when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
			response(200, "{\"id\":456,\"app_id\":123,\"expires_in\":100}"), response(200, "{\"id\":999}"));
		assertThatThrownBy(() -> client.verify("synthetic")).isInstanceOfSatisfying(CustomException.class,
			e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_INVALID));
		when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(503, "private provider details"));
		assertThatThrownBy(() -> client.verify("synthetic")).isInstanceOfSatisfying(CustomException.class,
			e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KAKAO_UNAVAILABLE));
	}
	@Test void lunarFutureAndUnagreedDataAreNotInvented() throws Exception {
		when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
			response(200, "{\"id\":456,\"app_id\":123,\"expires_in\":100}"),
			response(200, "{\"id\":456,\"kakao_account\":{\"gender\":\"male\",\"gender_needs_agreement\":true,\"birthday_type\":\"LUNAR\",\"birthyear\":\"2000\",\"birthday\":\"0101\"}}"));
		var identity = client.verify("synthetic");
		assertThat(identity.gender()).isNull();
		assertThat(identity.birthDate()).isNull();
	}
}

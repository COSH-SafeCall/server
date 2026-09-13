package com.safecall.service.auth.kakao;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.common.error.*;

@SuppressWarnings("unchecked")
class HttpKakaoUnlinkClientTest {
	private final HttpClient http=mock(HttpClient.class);
	private final JsonMapper mapper=JsonMapper.builder().build();
	private final HttpKakaoUnlinkClient client=new HttpKakaoUnlinkClient(http,mapper,"synthetic-admin-key");
	private HttpResponse<String> response(int status,String body) {
		HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);return response;
	}
	@Test void usesServerAdminAuthenticationAndFixedUnlinkEndpoint()throws Exception {
		var success=response(200,"{\"id\":12345}");when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(success);
		client.unlink("12345");var request=ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
		assertThat(request.getValue().method()).isEqualTo("POST");assertThat(request.getValue().uri().toString()).isEqualTo("https://kapi.kakao.com/v1/user/unlink");
		assertThat(request.getValue().headers().firstValue("Authorization")).contains("KakaoAK synthetic-admin-key");
		assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(5));
	}
	@Test void alreadyUnlinkedResponseCompletesRetryButOtherErrorsDoNot()throws Exception {
		var alreadyUnlinked=response(400,"{\"code\":-101}");when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(alreadyUnlinked);client.unlink("12345");
		for(var response:java.util.List.of(response(401,"{\"code\":-401}"),response(503,"private provider detail"),response(200,"{\"id\":99999}"),response(400,"{\"code\":-2}"))) {
			when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
			assertThatThrownBy(()->client.unlink("12345")).isInstanceOfSatisfying(CustomException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.KAKAO_UNAVAILABLE))
				.hasMessageNotContaining("private provider detail");
		}
	}
	@Test void missingAdminKeyAndInvalidSubjectNeverCallProvider() {
		assertThatThrownBy(()->new HttpKakaoUnlinkClient(http,mapper,"").unlink("12345")).isInstanceOf(CustomException.class);
		for(String subject:java.util.List.of("0","-1","123&other=456","9223372036854775808"))assertThatThrownBy(()->client.unlink(subject)).isInstanceOf(CustomException.class);
		verifyNoInteractions(http);
	}
	@Test void interruptedProviderCallKeepsInterruptFlagAndSanitizesFailure()throws Exception {
		when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException("synthetic"));
		try {assertThatThrownBy(()->client.unlink("12345")).isInstanceOf(CustomException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();}
		finally {Thread.interrupted();}
	}
}

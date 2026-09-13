package com.safecall.service.auth.kakao;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.common.error.*;

@Component
public class HttpKakaoUnlinkClient implements KakaoUnlinkClient {
	private final HttpClient http;
	private final JsonMapper mapper;
	private final String adminKey;
	@Autowired public HttpKakaoUnlinkClient(JsonMapper mapper,@Value("${app.oauth.kakao.admin-key:}") String adminKey) {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),mapper,adminKey);
	}
	HttpKakaoUnlinkClient(HttpClient http,JsonMapper mapper,String adminKey) { this.http=http;this.mapper=mapper;this.adminKey=adminKey; }
	@Override public void unlink(String subject) {
		try {
			if(adminKey.isBlank() || subject==null || !subject.matches("[1-9][0-9]{0,18}") || Long.parseLong(subject)<=0)
				throw new IllegalArgumentException();
			var request=HttpRequest.newBuilder(URI.create("https://kapi.kakao.com/v1/user/unlink")).timeout(Duration.ofSeconds(5))
				.header("Authorization","KakaoAK "+adminKey).header("Content-Type","application/x-www-form-urlencoded;charset=utf-8")
				.POST(HttpRequest.BodyPublishers.ofString("target_id_type=user_id&target_id="+subject)).build();
			var response=http.send(request,HttpResponse.BodyHandlers.ofString());
			if(response.body().length()>65536)throw new IllegalArgumentException();
			var root=mapper.readTree(response.body());
			if(response.statusCode()==200 && root.path("id").isIntegralNumber() && root.path("id").asString().equals(subject))return;
			// 카카오의 400/-101은 이미 앱 연결이 없는 사용자다. 응답 유실 뒤의 재시도도 완료 가능하다.
			if(response.statusCode()==400 && root.path("code").isIntegralNumber() && root.path("code").asInt()==-101)return;
			throw new IllegalArgumentException();
		} catch(InterruptedException exception) {
			Thread.currentThread().interrupt();throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
		} catch(Exception exception) { throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE); }
	}
}

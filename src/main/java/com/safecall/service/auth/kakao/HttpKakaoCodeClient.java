package com.safecall.service.auth.kakao;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.service.WebPolicy;
import com.safecall.service.common.error.*;
@Component
public class HttpKakaoCodeClient implements KakaoCodeClient {
	private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
	private final WebPolicy policy; private final KakaoClient identity; private final JsonMapper mapper;
	public HttpKakaoCodeClient(WebPolicy policy,KakaoClient identity,JsonMapper mapper){this.policy=policy;this.identity=identity;this.mapper=mapper;}
	private String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
	@Override public KakaoClient.KakaoIdentity exchange(String code,String redirectUri) {
		if(!redirectUri.equals(policy.redirectUri()))throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
		String body="grant_type=authorization_code&client_id="+encode(policy.clientId())+"&redirect_uri="+encode(redirectUri)+"&code="+encode(code);
		if(!policy.clientSecret().isBlank())body+="&client_secret="+encode(policy.clientSecret());
		try {
			var request=HttpRequest.newBuilder(URI.create("https://kauth.kakao.com/oauth/token")).timeout(Duration.ofSeconds(5))
				.header("Content-Type","application/x-www-form-urlencoded;charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body)).build();
			var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
			try(var stream=response.body()) {
				byte[] bytes=stream.readNBytes(65537);
				if(response.statusCode()!=200 || bytes.length>65536)throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);
				var root=mapper.readTree(bytes); var token=root.path("access_token");
				if(!token.isString() || token.asString().isBlank() || token.asString().length()>4096 || !"bearer".equalsIgnoreCase(root.path("token_type").asString()))throw new CustomException(ErrorCode.KAKAO_TOKEN_INVALID);
				return identity.verify(token.asString());
			}
		} catch(InterruptedException ex){Thread.currentThread().interrupt();throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);}
		catch(CustomException ex){throw ex;} catch(Exception ex){throw new CustomException(ErrorCode.KAKAO_UNAVAILABLE);}
	}
}

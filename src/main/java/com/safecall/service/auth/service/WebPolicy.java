package com.safecall.service.auth.service;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record WebPolicy(String origin, String clientId, String clientSecret, String redirectUri,
	String loginScopes, long anonymousSeconds, long guestSeconds, long memberSeconds, long sensitiveSeconds) {
	@Override public String toString(){return "WebPolicy[redacted]";}
	public WebPolicy(@Value("${app.web.origin}") String origin,
		@Value("${app.oauth.kakao.client-id}") String clientId, @Value("${app.oauth.kakao.client-secret}") String clientSecret,
		@Value("${app.oauth.kakao.redirect-uri}") String redirectUri, @Value("${app.oauth.kakao.login-scopes}") String loginScopes,
		@Value("${app.web.anonymous-seconds:600}") long anonymousSeconds, @Value("${app.web.guest-seconds:86400}") long guestSeconds,
		@Value("${app.web.member-seconds:1209600}") long memberSeconds, @Value("${app.web.sensitive-seconds:300}") long sensitiveSeconds) {
		URI uri=URI.create(origin);
		if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty()
			|| !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && java.util.Set.of("localhost","127.0.0.1").contains(uri.getHost()))
			|| !redirectUri.equals(origin+"/api/v1/auth/kakao/callback") || anonymousSeconds<1 || anonymousSeconds>600
			|| guestSeconds<1 || guestSeconds>86400 || memberSeconds<1 || memberSeconds>1209600 || sensitiveSeconds<1 || sensitiveSeconds>300)
			throw new IllegalStateException("Invalid web session policy.");
		this.origin=origin; this.clientId=clientId; this.clientSecret=clientSecret; this.redirectUri=redirectUri; this.loginScopes=loginScopes;
		this.anonymousSeconds=anonymousSeconds; this.guestSeconds=guestSeconds; this.memberSeconds=memberSeconds; this.sensitiveSeconds=sensitiveSeconds;
	}
}

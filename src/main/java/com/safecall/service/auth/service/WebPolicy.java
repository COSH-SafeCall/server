package com.safecall.service.auth.service;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record WebPolicy(String origin, long anonymousSeconds, long guestSeconds, long memberSeconds) {
	@Override public String toString(){return "WebPolicy[redacted]";}
	public WebPolicy(@Value("${app.web.origin}") String origin,
		@Value("${app.web.anonymous-seconds:600}") long anonymousSeconds, @Value("${app.web.guest-seconds:86400}") long guestSeconds,
		@Value("${app.web.member-seconds:1209600}") long memberSeconds) {
		URI uri=URI.create(origin);
		if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty()
			|| !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && java.util.Set.of("localhost","127.0.0.1").contains(uri.getHost()))
			|| anonymousSeconds<1 || anonymousSeconds>600 || guestSeconds<1 || guestSeconds>86400 || memberSeconds<1 || memberSeconds>1209600)
			throw new IllegalStateException("Invalid web session policy.");
		this.origin=origin;this.anonymousSeconds=anonymousSeconds;this.guestSeconds=guestSeconds;this.memberSeconds=memberSeconds;
	}
}

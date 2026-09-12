package com.safecall.service.auth.api;
import java.time.*;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.*;
import com.safecall.service.auth.service.AuthTransactions.SessionResult;
import com.safecall.service.common.error.*;
public final class WebCookies {
	public static final String SESSION="__Host-safecall-session";
	private WebCookies() {}
	public static String read(HttpServletRequest request) {
		String value=null;
		if(request.getCookies()!=null)for(Cookie c:request.getCookies())if(c.getName().equals(SESSION)) {
			if(value!=null)throw new CustomException(ErrorCode.INVALID_REQUEST); value=c.getValue();
		}
		return value;
	}
	public static void set(HttpServletResponse response,SessionResult result,Instant now) {
		if(result.cookie()!=null)response.addHeader("Set-Cookie",cookie(SESSION,result.cookie(),Math.max(0,Duration.between(now,result.view().expiresAt()).getSeconds())));
	}
	public static String cookie(String name,String value,long seconds) { return ResponseCookie.from(name,value).httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(seconds).build().toString(); }
	public static void clear(HttpServletResponse response) {response.addHeader("Set-Cookie",cookie(SESSION,"",0));}
}

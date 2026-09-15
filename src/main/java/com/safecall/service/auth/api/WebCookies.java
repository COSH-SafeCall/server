package com.safecall.service.auth.api;
import java.time.*;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.*;
import com.safecall.service.auth.service.AuthTransactions.SessionResult;
import com.safecall.service.common.error.*;
public final class WebCookies {
	public static final String SESSION="__Host-safecall-session";
	public static final String PROFILE_PREFILL="__Host-safecall-profile-prefill";
	private WebCookies() {}
	public static String read(HttpServletRequest request) {
		return read(request,SESSION,true);
	}
	public static String readProfilePrefill(HttpServletRequest request) {
		return read(request,PROFILE_PREFILL,false);
	}
	private static String read(HttpServletRequest request,String name,boolean rejectDuplicate) {
		String value=null;
		if(request.getCookies()!=null)for(Cookie c:request.getCookies())if(c.getName().equals(name)) {
			if(value!=null&&rejectDuplicate)throw new CustomException(ErrorCode.INVALID_REQUEST); value=c.getValue();
		}
		return value;
	}
	public static void set(HttpServletResponse response,SessionResult result,Instant now) {
		if(result.cookie()!=null)response.addHeader("Set-Cookie",cookie(SESSION,result.cookie(),Math.max(0,Duration.between(now,result.view().expiresAt()).getSeconds())));
	}
	public static void setProfilePrefill(HttpServletResponse response,String value) {
		if(value!=null)response.addHeader("Set-Cookie",cookie(PROFILE_PREFILL,value,600));
	}
	public static String cookie(String name,String value,long seconds) { return ResponseCookie.from(name,value).httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(seconds).build().toString(); }
	public static void clearProfilePrefill(HttpServletResponse response) {response.addHeader("Set-Cookie",cookie(PROFILE_PREFILL,"",0));}
	public static void clear(HttpServletResponse response) {response.addHeader("Set-Cookie",cookie(SESSION,"",0));clearProfilePrefill(response);}
}

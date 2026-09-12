package com.safecall.service.common.config;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.safecall.service.auth.api.WebCookies;
import com.safecall.service.auth.service.*;
import com.safecall.service.common.error.*;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
	private final java.security.SecureRandom random=new java.security.SecureRandom();
	private final ErrorWriter writer; private final AuthMaintenance maintenance; private final AuthTransactions auth; private final WebPolicy policy;
	public RequestFilter(ErrorWriter writer,AuthMaintenance maintenance,AuthTransactions auth,WebPolicy policy){this.writer=writer;this.maintenance=maintenance;this.auth=auth;this.policy=policy;}
	@Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
		response.setHeader("X-Request-Id",UUID.randomUUID().toString()); response.setHeader("Cache-Control","no-store");
		response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","no-referrer");
		response.setHeader("Permissions-Policy","microphone=(self), geolocation=(self), camera=()");
		byte[] nonceBytes=new byte[24];random.nextBytes(nonceBytes);String nonce=Base64.getEncoder().encodeToString(nonceBytes);
		request.setAttribute("cspNonce",nonce);
		response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self' 'nonce-"+nonce+"'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; connect-src 'self' https://generativelanguage.googleapis.com wss://generativelanguage.googleapis.com");
		try {
			String path=request.getRequestURI(),origin=request.getHeader("Origin");
			if(path.equals("/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal") && OAuthService.isKakaoInApp(request.getHeader("User-Agent")))throw new CustomException(ErrorCode.REAUTHENTICATION_REQUIRED);
			if(!path.startsWith("/api/")){chain.doFilter(request,response);return;}
			boolean callback=path.equals("/api/v1/auth/kakao/callback")&&request.getMethod().equals("GET");
			if(callback) {
				for(String name:List.of("state","code","error"))if(request.getParameterValues(name)!=null && request.getParameterValues(name).length!=1)throw new CustomException(ErrorCode.INVALID_REQUEST);
			} else if(origin!=null) {
				if(!origin.equals(policy.origin()) || Collections.list(request.getHeaders("Origin")).size()!=1)throw new CustomException(ErrorCode.ORIGIN_NOT_ALLOWED);
				response.setHeader("Access-Control-Allow-Origin",policy.origin());response.setHeader("Access-Control-Allow-Credentials","true");response.addHeader("Vary","Origin");
			}
			if(request.getMethod().equals("OPTIONS")) {
				if(!policy.origin().equals(origin))throw new CustomException(ErrorCode.ORIGIN_NOT_ALLOWED);
				response.setHeader("Access-Control-Allow-Methods","GET,POST,PATCH,DELETE,OPTIONS");
				response.setHeader("Access-Control-Allow-Headers","Content-Type,X-CSRF-Token,X-Call-Page-Key,Idempotency-Key");response.setStatus(204);return;
			}
			if(path.equals("/api/v1/auth/session")) {
				String site=request.getHeader("Sec-Fetch-Site");
				if(site!=null ? !site.equals("same-origin") : !policy.origin().equals(origin) && !sameReferer(request.getHeader("Referer")))throw new CustomException(ErrorCode.ORIGIN_NOT_ALLOWED);
			}
			if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
				if(!policy.origin().equals(origin))throw new CustomException(ErrorCode.ORIGIN_NOT_ALLOWED);
				String cookie=WebCookies.read(request);boolean logout=path.equals("/api/v1/auth/logout");
				if(!(logout&&cookie==null)) {
					if(Collections.list(request.getHeaders("X-CSRF-Token")).size()!=1)throw new CustomException(ErrorCode.CSRF_INVALID);
					auth.verifyCsrf(cookie,request.getHeader("X-CSRF-Token"),logout);
				}
			}
			validateUuidHeader(request,"Idempotency-Key");
			if(request.getMethod().equals("POST")&&Set.of("/api/v1/auth/guest","/api/v1/auth/kakao/authorization").contains(path))maintenance.checkRate(request.getRemoteAddr());
			chain.doFilter(request,response);
		} catch(CustomException ex){if(!response.isCommitted())writer.write(request,response,ex);}
		catch(Exception ex){if(!response.isCommitted())writer.write(request,response,new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));}
	}
	private boolean sameReferer(String ref) {
		if(ref==null)return false;
		try {URI uri=URI.create(ref);return uri.getUserInfo()==null && policy.origin().equals(uri.getScheme()+"://"+uri.getRawAuthority());}catch(IllegalArgumentException ex){return false;}
	}
	private void validateUuidHeader(HttpServletRequest req,String name) {
		String key=req.getHeader(name);if(key==null)return;
		try{if(!UUID.fromString(key).toString().equalsIgnoreCase(key)||Collections.list(req.getHeaders(name)).size()!=1)throw new IllegalArgumentException();}
		catch(IllegalArgumentException ex){throw new CustomException(ErrorCode.INVALID_REQUEST);}
	}
}

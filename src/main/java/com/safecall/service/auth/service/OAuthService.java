package com.safecall.service.auth.service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.kakao.KakaoCodeClient;
import com.safecall.service.common.error.*;
@Service
public class OAuthService {
	private final OAuthTransactions transactions; private final KakaoCodeClient kakao; private final WebPolicy policy; private final KakaoProfilePrefillCodec prefill;
	public OAuthService(OAuthTransactions transactions,KakaoCodeClient kakao,WebPolicy policy,KakaoProfilePrefillCodec prefill) {this.transactions=transactions;this.kakao=kakao;this.policy=policy;this.prefill=prefill;}
	private static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
	public static boolean isKakaoInApp(String userAgent){return userAgent!=null && userAgent.toUpperCase(java.util.Locale.ROOT).contains("KAKAOTALK");}
	public AuthorizationView start(String cookie,AuthorizationRequest request,String userAgent) {
		if(request.purpose()==Purpose.REAUTH && isKakaoInApp(userAgent))throw new CustomException(ErrorCode.REAUTHENTICATION_REQUIRED);
		var result=transactions.start(cookie,request);
		String url="https://kauth.kakao.com/oauth/authorize?response_type=code&client_id="+encode(policy.clientId())+"&redirect_uri="+encode(policy.redirectUri())+"&state="+encode(result.state());
		if(request.purpose()==Purpose.REAUTH)url+="&prompt=login";
		else if(!policy.loginScopes().isBlank())url+="&scope="+encode(policy.loginScopes());
		return new AuthorizationView(url,result.expiresAt());
	}
	public record Callback(String location,AuthTransactions.SessionResult session,String profilePrefill) {}
	public Callback callback(String cookie,String state,String code,String error,String userAgent) {
		OAuthTransactions.Attempt attempt=null;
		try {
			attempt=transactions.claim(cookie,state);
			if(attempt==null)return new Callback("/login?reason=oauth_failed",null,null);
			if(attempt.purpose()==Purpose.REAUTH && isKakaoInApp(userAgent)){transactions.fail(attempt.id());return new Callback("/login?reason=external_browser_required",null,null);}
			if((code==null)==(error==null) || code!=null && (code.isBlank()||code.length()>4096))throw new CustomException(ErrorCode.INVALID_REQUEST);
			if(error!=null) {transactions.fail(attempt.id());return new Callback(error.equals("access_denied")?"/login?reason=cancelled":"/login?reason=oauth_failed",null,null);}
			// Network work occurs between the claim and completion transactions, never with DB locks held.
			var identity=kakao.exchange(code,attempt.redirectUri());
			var session=transactions.complete(attempt,cookie,identity);
			String profilePrefill=attempt.purpose()==Purpose.LOGIN?prefill.encode(session.cookie(),identity):null;
			return new Callback(attempt.purpose()==Purpose.REAUTH?"/settings":"/onboarding/profile",session,profilePrefill);
		} catch(RuntimeException ex) {
			if(attempt!=null)transactions.fail(attempt.id());
			if(ex instanceof CustomException ce && ce.errorCode()==ErrorCode.REAUTH_ACCOUNT_MISMATCH)throw ce;
			return new Callback(ex instanceof CustomException ce && ce.errorCode()==ErrorCode.ACCOUNT_DELETION_PENDING?"/login?reason=account_cleanup_pending":"/login?reason=oauth_failed",null,null);
		}
	}
	public ProfilePrefill consumeProfilePrefill(String sessionCookie,String encoded) {return prefill.decode(sessionCookie,encoded);}
}

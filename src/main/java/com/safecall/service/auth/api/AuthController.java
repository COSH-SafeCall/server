package com.safecall.service.auth.api;
import java.time.Clock;
import java.util.UUID;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.service.*;
@RestController
@RequestMapping("/api/v1")
public class AuthController {
	private final AuthTransactions auth; private final OAuthService oauth; private final Clock clock;
	public AuthController(AuthTransactions auth,OAuthService oauth,Clock clock){this.auth=auth;this.oauth=oauth;this.clock=clock;}
	@GetMapping("/auth/session") @Operation(operationId="A05",summary="인증 상태 및 CSRF 초기화")
	public SessionView session(HttpServletRequest req,HttpServletResponse res){var result=auth.bootstrap(WebCookies.read(req));WebCookies.set(res,result,clock.instant());return result.view();}
	@PostMapping(value="/auth/guest",consumes="application/json") @Operation(operationId="A01",summary="게스트 진입")
	public SessionView guest(HttpServletRequest req,HttpServletResponse res,@Valid @RequestBody EmptyRequest body){var result=auth.guest(WebCookies.read(req));WebCookies.set(res,result,clock.instant());return result.view();}
	@PostMapping(value="/auth/kakao/authorization",consumes="application/json") @Operation(operationId="A02",summary="동의 후 카카오 OAuth 시작")
	public AuthorizationView authorization(HttpServletRequest req,@Valid @RequestBody AuthorizationRequest body){return oauth.start(WebCookies.read(req),body,req.getHeader("User-Agent"));}
	@GetMapping("/auth/kakao/callback") @Operation(operationId="A02_CALLBACK",summary="일회성 OAuth 콜백")
	public ResponseEntity<Void> callback(HttpServletRequest req,HttpServletResponse res,@RequestParam String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error){
		var result=oauth.callback(WebCookies.read(req),state,code,error,req.getHeader("User-Agent"));if(result.session()!=null)WebCookies.set(res,result.session(),clock.instant());
		return ResponseEntity.status(303).header("Location",result.location()).build();
	}
	@PostMapping(value="/auth/logout",consumes="application/json") @Operation(operationId="A04",summary="현재 웹 세션 로그아웃")
	public ResponseEntity<Void> logout(HttpServletRequest req,HttpServletResponse res,@Valid @RequestBody EmptyRequest body){
		try{auth.logout(WebCookies.read(req));}catch(org.springframework.dao.DataAccessException ex){throw new com.safecall.service.common.error.CustomException(com.safecall.service.common.error.ErrorCode.LOGOUT_FAILED);}
		WebCookies.clear(res);return ResponseEntity.noContent().build();
	}
	@GetMapping("/onboarding") @Operation(operationId="A06",summary="온보딩 단계 조회")
	public OnboardingView onboarding(HttpServletRequest req){return auth.onboarding(WebCookies.read(req));}
	@PostMapping(value="/onboarding/advance",consumes="application/json") @Operation(operationId="A07",summary="온보딩 단계 진행")
	public OnboardingView advance(HttpServletRequest req,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody AdvanceRequest body){return auth.advance(WebCookies.read(req),body,key);}
}

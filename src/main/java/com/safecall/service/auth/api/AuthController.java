package com.safecall.service.auth.api;
import java.time.Clock;
import java.util.UUID;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.service.*;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;
@RestController
@RequestMapping("/api/v1")
public class AuthController {
	private final AuthTransactions auth; private final VirtualLoginTransactions virtualLogin; private final Clock clock;
	public AuthController(AuthTransactions auth,VirtualLoginTransactions virtualLogin,Clock clock){this.auth=auth;this.virtualLogin=virtualLogin;this.clock=clock;}
	@GetMapping("/auth/session") @Operation(operationId="A05",summary="인증 상태 및 CSRF 초기화")
	public SessionView session(HttpServletRequest req,HttpServletResponse res){
		var result=auth.bootstrap(WebCookies.read(req));WebCookies.set(res,result,clock.instant());return result.view();
	}
	@PostMapping(value="/auth/guest",consumes="application/json") @Operation(operationId="A01",summary="게스트 진입")
	public SessionView guest(HttpServletRequest req,HttpServletResponse res,@Valid @RequestBody EmptyRequest body){var result=auth.guest(WebCookies.read(req));WebCookies.set(res,result,clock.instant());return result.view();}
	@PostMapping(value="/auth/virtual",consumes="application/json") @Operation(operationId="A02",summary="가상 회원 로그인")
	public SessionView virtual(HttpServletRequest req,HttpServletResponse res,@Valid @RequestBody EmptyRequest body){
		var result=virtualLogin.login(WebCookies.read(req));WebCookies.set(res,result,clock.instant());return result.view();
	}
	@PostMapping(value="/auth/logout",consumes="application/json") @Operation(operationId="A04",summary="현재 웹 세션 로그아웃")
	public ResponseEntity<Void> logout(HttpServletRequest req,HttpServletResponse res,@Valid @RequestBody EmptyRequest body){
		try{auth.logout(WebCookies.read(req));}catch(DataAccessException ex){throw new CustomException(ErrorCode.LOGOUT_FAILED);}
		WebCookies.clear(res);return ResponseEntity.noContent().build();
	}
}

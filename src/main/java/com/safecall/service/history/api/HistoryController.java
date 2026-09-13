package com.safecall.service.history.api;

import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import com.safecall.service.auth.api.WebCookies;
import com.safecall.service.auth.service.OAuthService;
import com.safecall.service.common.error.*;
import com.safecall.service.history.api.HistoryDtos.*;
import com.safecall.service.history.service.HistoryService;
import com.safecall.service.user.api.UserDtos.DeletionView;

@RestController
@RequestMapping(value="/api/v1",produces="application/json")
public class HistoryController {
	private final HistoryService service;
	private final Clock clock;
	public HistoryController(HistoryService service,Clock clock) { this.service=service;this.clock=clock; }
	@GetMapping("/me/usage-history")
	@Operation(operationId="R01",summary="R01 · 종료·실패 통화 이용 기록 조회",tags="9. 이용 기록·데이터 삭제")
	@Parameter(name="limit",in=ParameterIn.QUERY,schema=@Schema(type="integer",minimum="1",maximum="100",defaultValue="20"))
	@Parameter(name="cursor",in=ParameterIn.QUERY,schema=@Schema(type="string",minLength=16,maxLength=512,pattern="^[A-Za-z0-9_-]+$"))
	public UsageHistoryView history(HttpServletRequest request) {
		if (request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null
			|| request.getParameterMap().keySet().stream().anyMatch(name->!Set.of("limit","cursor").contains(name)))
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		String[] limits=request.getParameterValues("limit"),cursors=request.getParameterValues("cursor");
		int limit=20;
		if (limits!=null) {
			if (limits.length!=1 || !limits[0].matches("[0-9]{1,3}")) throw new CustomException(ErrorCode.INVALID_REQUEST);
			limit=Integer.parseInt(limits[0]);
			if (limit<1 || limit>100) throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
		if (cursors!=null && cursors.length!=1) throw new CustomException(ErrorCode.INVALID_CURSOR);
		return service.history(WebCookies.read(request),limit,cursors==null?null:cursors[0]);
	}
	@PostMapping(value="/me/data-deletions",consumes="application/json")
	@Operation(operationId="R02",summary="R02 · 계정 또는 이용 기록 삭제 접수",tags="9. 이용 기록·데이터 삭제",
		description="삭제 범위와 복구 불가능 항목을 안내한 뒤 isConfirmed=true로 접수합니다. ACCOUNT는 동일 계정의 최근 5분 이내 명시적 재인증이 필요합니다. 접수증은 HttpOnly 쿠키로만 발급합니다.")
	public ResponseEntity<DeletionView> delete(HttpServletRequest request,@Valid @RequestBody DeletionRequest body,
		@RequestHeader("Idempotency-Key") UUID key) {
		var result=service.request(WebCookies.read(request),body,key,OAuthService.isKakaoInApp(request.getHeader("User-Agent")));
		return ResponseEntity.accepted().header("Set-Cookie",WebCookies.cookie("__Host-safecall-deletion",result.receiptToken(),
			Math.max(0,Duration.between(clock.instant(),result.receiptExpiresAt()).getSeconds()))).body(result.view());
	}
	@GetMapping("/data-deletions/{jobId}")
	@Operation(operationId="R03",summary="R03 · 삭제 작업 상태 조회",tags="9. 이용 기록·데이터 삭제",
		description="소유 회원 또는 삭제 접수증 쿠키로 조회합니다. 탈퇴 후 접수증을 잃거나 만료되면 재인증으로 복구할 수 없습니다.")
	public DeletionView status(HttpServletRequest request,@PathVariable UUID jobId) {
		String receipt=null;
		if (request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals("__Host-safecall-deletion")) {
			if(receipt!=null) throw new CustomException(ErrorCode.INVALID_REQUEST);receipt=cookie.getValue();
		}
		return service.status(WebCookies.read(request),receipt,jobId);
	}
}

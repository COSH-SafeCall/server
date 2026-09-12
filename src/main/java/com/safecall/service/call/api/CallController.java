package com.safecall.service.call.api;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.*;
import com.safecall.service.call.api.CallDtos.*;
import com.safecall.service.call.service.CallService;
import com.safecall.service.common.error.*;

@RestController
@RequestMapping(value="/api/v1/calls",produces="application/json")
@SecurityRequirement(name="webSession")
public class CallController {
	private final CallService service;
	public CallController(CallService service) { this.service=service; }
	@PostMapping(consumes="application/json")
	@Operation(operationId="C01",summary="C01 · AI 통화 생성",tags="8. AI 안심 통화",description="완료된 게스트/회원 세션. 마이크 권한, 회원 필수 동의, 발행된 12개 프롬프트가 필요합니다. 생성 후 heartbeat를 5초 간격으로 전송합니다.")
	@ApiResponse(responseCode="202",description="통화 준비 중",content=@Content(schema=@Schema(implementation=CallView.class)))
	public ResponseEntity<CallView> create(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String access,
		@RequestHeader("Idempotency-Key") UUID key,@RequestHeader("X-Call-Page-Key") String page,@Valid @RequestBody CreateCall body) {
		var view=service.create(access,page,body,key);
		return ResponseEntity.accepted().location(URI.create("/api/v1/calls/"+view.id())).body(view);
	}
	@GetMapping("/{callId}")
	@Operation(operationId="C02",summary="C02 · 통화 조회",tags="8. AI 안심 통화")
	public CallView get(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String access,@PathVariable UUID callId,@RequestHeader("X-Call-Page-Key") String page) {
		return service.get(access,page,callId);
	}
	@GetMapping("/{callId}/connection")
	@Operation(operationId="C03",summary="C03 · Gemini 단기 연결 정보",tags="8. AI 안심 통화",description="발급 중 202, 준비 완료 200. 조회로 토큰을 새로 발급하지 않습니다. 반환된 토큰은 현재 페이지 메모리에서만 사용합니다.")
	@ApiResponse(responseCode="200",description="연결 정보 준비 완료",content=@Content(schema=@Schema(implementation=ConnectionView.class)))
	@ApiResponse(responseCode="202",description="발급 중",content=@Content(schema=@Schema(implementation=IssuingView.class)))
	public ResponseEntity<?> connection(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String access,@PathVariable UUID callId,@RequestHeader("X-Call-Page-Key") String page,@RequestParam(required=false) UUID grantId) {
		Object view=service.connection(access,page,callId,grantId);
		return ResponseEntity.status(view instanceof IssuingView?202:200).body(view);
	}
	@PostMapping(value="/{callId}/events",consumes="application/json")
	@Operation(operationId="C04",summary="C04 · 통화 사건 기록",tags="8. AI 안심 통화",description="CONNECTED → RINGING_SHOWN → ANSWERED 순서. FAILED에만 errorCode가 필수입니다. 동일 사건 재시도는 현재 통화 상태를 반환합니다.")
	public CallView event(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String access,@PathVariable UUID callId,@RequestHeader("X-Call-Page-Key") String page,
		@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CallEvent body) {
		return service.event(access,page,callId,body,key);
	}
	@PostMapping(value="/{callId}/heartbeat",consumes="application/json")
	@Operation(operationId="C05",summary="C05 · 통화 생존 신호",tags="8. AI 안심 통화",description="본문 {}. 5초 간격, 30초 lease 만료 시 종료. 통화 상한과 version을 연장하지 않습니다.")
	public HeartbeatView heartbeat(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String access,@PathVariable UUID callId,@RequestHeader("X-Call-Page-Key") String page,
		@Valid @RequestBody HeartbeatRequest body) { return service.heartbeat(access,page,callId); }
	@PostMapping(value="/{callId}/end",consumes="application/json")
	@Operation(operationId="C06",summary="C06 · 통화 종료",tags="8. AI 안심 통화",description="반복 안전 종료. 브라우저는 서버 응답을 기다리지 않고 소켓·마이크·재생을 먼저 중지합니다.")
	public CallView end(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String access,@PathVariable UUID callId,@RequestHeader("X-Call-Page-Key") String page,
		@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody EndCall body) { return service.end(access,page,callId,body,key); }
	@PostMapping(value="/{callId}/connection-renewals",consumes="application/json")
	@Operation(operationId="C07",summary="C07 · 같은 통화 연결 재개")
	public ResponseEntity<GrantView> renew(@CookieValue(value="__Host-safecall-session",required=false) String cookie,@RequestHeader("X-Call-Page-Key") String page,
		@PathVariable UUID callId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody RenewalRequest body){
		var result=service.renew(cookie,page,callId,body,key);return ResponseEntity.accepted().location(URI.create("/api/v1/calls/"+callId+"/connection?grantId="+result.grantId())).body(result);
	}
}

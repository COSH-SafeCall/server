package com.safecall.service.telemetry.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import com.safecall.service.auth.api.WebCookies;
import com.safecall.service.telemetry.api.TelemetryDtos.*;
import com.safecall.service.telemetry.service.TelemetryService;

@RestController
@RequestMapping(value="/api/v1/telemetry",produces="application/json")
public class TelemetryController {
	private final TelemetryService service;
	public TelemetryController(TelemetryService service) { this.service=service; }
	@PostMapping(value="/events",consumes="application/json")
	@Operation(operationId="O01",summary="O01 · 개인정보 없는 운영 사건 수집",tags="10. 운영 관측",
		description="게스트·회원 세션에서 1~20건을 접수합니다. eventId로 중복을 제외하고 세션별 분당 신규 120건을 허용합니다. 배치 실패 시 전체 취소하며 통화 상태는 변경하지 않습니다.")
	public ResponseEntity<EventCounts> events(HttpServletRequest request,@Valid @RequestBody EventBatch body) {
		return ResponseEntity.accepted().body(service.accept(WebCookies.read(request),body));
	}
}

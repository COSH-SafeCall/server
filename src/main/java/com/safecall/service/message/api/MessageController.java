package com.safecall.service.message.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.safecall.service.auth.api.WebCookies;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;
import com.safecall.service.common.error.ErrorResponse;
import com.safecall.service.message.api.MessageDtos.*;
import com.safecall.service.message.service.MessageService;

@RestController
public class MessageController {
	private final MessageService service;
	public MessageController(MessageService service) { this.service = service; }

	@GetMapping(value="/api/v1/message-composer", produces="application/json")
	@Operation(operationId="M01", summary="M01 · 안심 메시지 작성 자료 조회 및 자격 재검증", tags="8. 안심 메시지",
		description="회원 전용. SAFETY는 COMPLETE, TEST는 MESSAGE_TEST 또는 COMPLETE 단계에서 조회합니다. "
			+ "최신 보호자와 본인 번호를 마스킹한 작성 자료만 반환하며, 응답은 캐시하지 않습니다. 실제 SMS를 발송하지 않습니다.")
	@Parameter(name="mode", in=ParameterIn.QUERY, description="생략하면 SAFETY. 빈 값과 중복 값은 허용하지 않습니다.",
		schema=@Schema(implementation=Mode.class, defaultValue="SAFETY"))
	@ApiResponses({
		@ApiResponse(responseCode="200", description="작성 자료 조회 성공", useReturnTypeSchema=true),
		@ApiResponse(responseCode="400", description="INVALID_REQUEST", content=@Content(schema=@Schema(implementation=ErrorResponse.class))),
		@ApiResponse(responseCode="401", description="SESSION_EXPIRED", content=@Content(schema=@Schema(implementation=ErrorResponse.class))),
		@ApiResponse(responseCode="403", description="LOGIN_REQUIRED / CONSENT_REQUIRED / ONBOARDING_REQUIRED / ORIGIN_NOT_ALLOWED", content=@Content(schema=@Schema(implementation=ErrorResponse.class))),
		@ApiResponse(responseCode="409", description="PROFILE_REQUIRED / CONTACT_REQUIRED / CALL_ALREADY_OPEN / DATA_CLEANUP_PENDING", content=@Content(schema=@Schema(implementation=ErrorResponse.class)))
	})
	public ResponseEntity<MessageComposerView> compose(HttpServletRequest request) {
		// 좌표·완성 URL·사용자 식별자 등 명세 밖의 입력을 조용히 수용하지 않는다.
		if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		if (request.getParameterMap().keySet().stream().anyMatch(name -> !name.equals("mode")))
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		String[] values = request.getParameterValues("mode");
		Mode mode = Mode.SAFETY;
		if (values != null) {
			if (values.length != 1) throw new CustomException(ErrorCode.INVALID_REQUEST);
			try { mode = Mode.valueOf(values[0]); }
			catch (IllegalArgumentException exception) { throw new CustomException(ErrorCode.INVALID_REQUEST); }
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.compose(WebCookies.read(request), mode));
	}
}

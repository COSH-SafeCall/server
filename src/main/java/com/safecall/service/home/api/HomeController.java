package com.safecall.service.home.api;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.common.error.*;
import com.safecall.service.home.api.HomeDtos.*;
import com.safecall.service.home.service.HomeService;

@RestController
@SecurityRequirement(name="webSession")
public class HomeController {
	private final HomeService service;
	private final JsonMapper mapper;
	public HomeController(HomeService service, JsonMapper mapper) { this.service=service; this.mapper=mapper; }
	@GetMapping(value="/api/v1/home",produces="application/json")
	@Operation(operationId="H01",summary="H01 · 홈 기능 가능 여부 조회",tags="7. 홈·통화 선택지",
		description="게스트·회원 세션 필요. 메시지 작성 자격과 차단 사유를 조회합니다. 브라우저 권한이나 실제 위치 취득 성공을 보장하지 않습니다.")
	public HomeView home(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization) {
		return service.home(authorization);
	}
	@GetMapping(value="/api/v1/call-options",produces="application/json")
	@Operation(operationId="H02",summary="H02 · 상황·통화 상대 조회",tags="7. 홈·통화 선택지",
		description="게스트·회원 세션 필요. 프롬프트 발행과 무관하게 고정 선택지를 반환합니다. If-None-Match에는 이전 ETag를 입력합니다.")
	@ApiResponse(responseCode="200",description="고정 선택지 조회 성공",useReturnTypeSchema=true)
	@ApiResponse(responseCode="304",description="변경 없음 (인증 검증 후 반환)",content=@Content)
	public ResponseEntity<CallOptionsView> options(
		@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@RequestHeader(value="If-None-Match",required=false) String ifNoneMatch, WebRequest request) {
		var body=service.callOptions(authorization);
		String etag;
		try { etag="\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)))+"\""; }
		catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("Catalog digest unavailable."); }
		// Revalidate even for an unchanged public catalog so an expired session cannot use a cache hit.
		var cache=CacheControl.noCache().cachePrivate();
		if (request.checkNotModified(etag)) return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build();
		return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body);
	}
}

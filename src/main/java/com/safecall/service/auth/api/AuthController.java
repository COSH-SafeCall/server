package com.safecall.service.auth.api;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.service.AuthService;
import com.safecall.service.common.error.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
	private final AuthService service;
	public AuthController(AuthService service) { this.service = service; }
	@PostMapping(value="/auth/guest", consumes="application/json", produces="application/json")
	public ResponseEntity<AuthResponse> guest(@Valid @RequestBody GuestRequest body,
		@RequestHeader("Idempotency-Key") UUID key,
		@RequestHeader(value="Authorization", required=false) String authorization) {
		return ResponseEntity.status(201).body(service.guest(body, key, bearer(authorization, false)));
	}
	@PostMapping(value="/auth/kakao", consumes="application/json", produces="application/json")
	public AuthResponse kakao(@Valid @RequestBody KakaoRequest body, @RequestHeader("Idempotency-Key") UUID key,
		@RequestHeader(value="Authorization", required=false) String authorization) {
		return service.kakao(body, key, bearer(authorization, false));
	}
	@PostMapping(value="/auth/refresh", consumes="application/json", produces="application/json")
	public Tokens refresh(@Valid @RequestBody RefreshRequest body, @RequestHeader("Idempotency-Key") UUID key) {
		return service.refresh(body, key);
	}
	@PostMapping(value="/auth/logout", consumes="application/json")
	public ResponseEntity<Void> logout(@RequestBody EmptyRequest body, @RequestHeader("Idempotency-Key") UUID key,
		@RequestHeader(value="Authorization", required=false) String authorization) {
		service.logout(bearer(authorization, true), key);
		return ResponseEntity.noContent().build();
	}
	@GetMapping(value="/session", produces="application/json")
	public SessionView session(@RequestHeader(value="Authorization", required=false) String authorization) {
		return service.session(bearer(authorization, true));
	}
	@GetMapping(value="/onboarding", produces="application/json")
	public OnboardingView onboarding(@RequestHeader(value="Authorization", required=false) String authorization) {
		return service.onboarding(bearer(authorization, true));
	}
	@PostMapping(value="/onboarding/advance", consumes="application/json", produces="application/json")
	public OnboardingView advance(@Valid @RequestBody AdvanceRequest body, @RequestHeader("Idempotency-Key") UUID key,
		@RequestHeader(value="Authorization", required=false) String authorization) {
		return service.advance(bearer(authorization, true), body, key);
	}
	private String bearer(String value, boolean isRequired) {
		if (value == null && !isRequired) return null;
		if (value == null || !value.startsWith("Bearer ") || value.length() <= 7 || value.length() > 4103) {
			throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		return value.substring(7);
	}
}

package com.safecall.service.user.api;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.safecall.service.auth.api.AuthDtos.ProfileView;
import com.safecall.service.common.error.*;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.service.UserTransactions;

@RestController
@RequestMapping(value="/api/v1/me",produces="application/json")
@SecurityRequirement(name="accessToken")
public class UserController {
	private final UserTransactions service;
	public UserController(UserTransactions service) { this.service=service; }
	private String bearer(String value) {
		if (value==null || !value.startsWith("Bearer ") || value.length()<=7 || value.length()>4103) throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
		return value.substring(7);
	}
	@GetMapping("/profile")
	@Operation(operationId="U01",summary="U01 · 기본 정보 조회",tags="3. 사용자 정보")
	public ProfileView profile(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization) {
		return service.profile(bearer(authorization));
	}
	@PutMapping(value="/profile",consumes="application/json")
	@Operation(operationId="U02",summary="U02 · 기본 정보 확인·수정",tags="3. 사용자 정보")
	public ProfileView updateProfile(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@Valid @RequestBody ProfileRequest body) { return service.updateProfile(bearer(authorization),body); }
	@GetMapping("/consents")
	@Operation(operationId="U04",summary="U04 · 동의 내역 조회",tags="4. 문서·동의")
	public Items<ConsentView> consents(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization) {
		return service.consents(bearer(authorization));
	}
	@PostMapping(value="/consents",consumes="application/json")
	@Operation(operationId="U05",summary="U05 · 동의·거부 기록",tags="4. 문서·동의")
	public Items<ConsentView> decide(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@Valid @RequestBody DecisionsRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		return service.decide(bearer(authorization),body,key);
	}
	@PostMapping(value="/consents/{code}/withdraw",consumes="application/json")
	@Operation(operationId="U06",summary="U06 · 동의 철회 및 데이터 정리 접수",tags="4. 문서·동의",
		description="PRIVACY_PROCESSING 철회 시 계정 API 접근이 즉시 차단됩니다. 같은 키·본문으로 60초 이내 접수증 재조회가 가능합니다. ACCOUNT 삭제 worker와 R03 상태 조회는 6장 후속 구현입니다.")
	public WithdrawalView withdraw(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@PathVariable String code,@Valid @RequestBody WithdrawRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		return service.withdraw(bearer(authorization),code,body,key);
	}
	@GetMapping("/emergency-contacts")
	@Operation(operationId="U07",summary="U07 · 비상 연락망 조회",tags="5. 비상 연락망")
	public Items<ContactView> contacts(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization) {
		return service.contacts(bearer(authorization));
	}
	@PostMapping(value="/emergency-contacts",consumes="application/json")
	@Operation(operationId="U08",summary="U08 · 비상 연락망 등록",tags="5. 비상 연락망")
	public ResponseEntity<ContactView> createContact(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@Valid @RequestBody ContactRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		return ResponseEntity.status(201).body(service.createContact(bearer(authorization),body,key));
	}
	@PutMapping(value="/emergency-contacts/{contactId}",consumes="application/json")
	@Operation(operationId="U09",summary="U09 · 비상 연락망 수정",tags="5. 비상 연락망")
	public ContactView updateContact(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@PathVariable UUID contactId,@Valid @RequestBody ContactUpdate body) {
		return service.updateContact(bearer(authorization),contactId,body);
	}
	@DeleteMapping("/emergency-contacts/{contactId}")
	@Operation(operationId="U10",summary="U10 · 비상 연락망 삭제",tags="5. 비상 연락망")
	public ResponseEntity<Void> deleteContact(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@PathVariable UUID contactId,@RequestParam @Positive long expectedVersion,@RequestHeader("Idempotency-Key") UUID key) {
		service.deleteContact(bearer(authorization),contactId,expectedVersion,key); return ResponseEntity.noContent().build();
	}
	@GetMapping("/settings")
	@Operation(operationId="U11",summary="U11 · 사용자 설정 조회",tags="6. 사용자 설정")
	public SettingView settings(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization) {
		return service.settings(bearer(authorization));
	}
	@PatchMapping(value="/settings",consumes="application/json")
	@Operation(operationId="U12",summary="U12 · 가상 수신 알림 방식 변경",tags="6. 사용자 설정")
	public SettingView updateSettings(@Parameter(hidden=true) @RequestHeader(value="Authorization",required=false) String authorization,
		@Valid @RequestBody SettingRequest body) { return service.updateSettings(bearer(authorization),body); }
}

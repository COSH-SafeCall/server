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
@SecurityRequirement(name="webSession")
public class UserController {
	private final UserTransactions service;
	private final java.time.Clock clock;
	public UserController(UserTransactions service,java.time.Clock clock) { this.service=service;this.clock=clock; }
	@GetMapping("/profile")
	@Operation(operationId="U01",summary="U01 · 기본 정보 조회",tags="3. 사용자 정보")
	public ProfileView profile(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization) {
		return service.profile(authorization);
	}
	@PatchMapping(value="/profile",consumes="application/json")
	@Operation(operationId="U02",summary="U02 · 기본 정보 확인·수정",tags="3. 사용자 정보")
	public ProfileView updateProfile(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@Valid @RequestBody ProfileRequest body,@RequestHeader("Idempotency-Key") UUID key) { return service.updateProfile(authorization,body,key); }
	@GetMapping("/consents")
	@Operation(operationId="U04",summary="U04 · 동의 내역 조회",tags="4. 문서·동의")
	public Items<ConsentView> consents(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization) {
		return service.consents(authorization);
	}
	@PostMapping(value="/consents",consumes="application/json")
	@Operation(operationId="U05",summary="U05 · 동의·거부 기록",tags="4. 문서·동의")
	public Items<ConsentView> decide(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@Valid @RequestBody DecisionsRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		return service.decide(authorization,body,key);
	}
	@PostMapping(value="/consents/{code}/withdrawal",consumes="application/json")
	@Operation(operationId="U06",summary="U06 · 동의 철회 및 데이터 정리 접수",tags="4. 문서·동의",
		description="PRIVACY_PROCESSING 철회 시 계정 API 접근이 즉시 차단됩니다. 같은 키·본문으로 60초 이내 접수증 재조회가 가능합니다. 로컬 삭제 후 LOCAL_DELETED에서 외부 연결 정리를 수행하며 R03으로 상태를 조회합니다.")
	public ResponseEntity<DeletionView> withdraw(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@PathVariable String code,@Valid @RequestBody WithdrawRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		var receipt=service.withdraw(authorization,code,body,key);
		return ResponseEntity.accepted().header("Set-Cookie",com.safecall.service.auth.api.WebCookies.cookie("__Host-safecall-deletion",receipt.receiptToken(),Math.max(0,java.time.Duration.between(clock.instant(),receipt.receiptExpiresAt()).getSeconds())))
			.body(receipt.view());
	}
	@GetMapping("/emergency-contacts")
	@Operation(operationId="U07",summary="U07 · 비상 연락망 조회",tags="5. 비상 연락망")
	public Items<ContactView> contacts(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization) {
		return service.contacts(authorization);
	}
	@PostMapping(value="/emergency-contacts",consumes="application/json")
	@Operation(operationId="U08",summary="U08 · 비상 연락망 등록",tags="5. 비상 연락망")
	public ResponseEntity<ContactView> createContact(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@Valid @RequestBody ContactRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		var contact=service.createContact(authorization,body,key);
		return ResponseEntity.created(java.net.URI.create("/api/v1/me/emergency-contacts/"+contact.id())).body(contact);
	}
	@PatchMapping(value="/emergency-contacts/{contactId}",consumes="application/json")
	@Operation(operationId="U09",summary="U09 · 비상 연락망 수정",tags="5. 비상 연락망")
	public ContactView updateContact(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@PathVariable UUID contactId,@Valid @RequestBody ContactUpdate body,@RequestHeader("Idempotency-Key") UUID key) {
		return service.updateContact(authorization,contactId,body,key);
	}
	@DeleteMapping("/emergency-contacts/{contactId}")
	@Operation(operationId="U10",summary="U10 · 비상 연락망 삭제",tags="5. 비상 연락망")
	public ResponseEntity<Void> deleteContact(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@PathVariable UUID contactId,@Valid @RequestBody DeleteContactRequest body,@RequestHeader("Idempotency-Key") UUID key) {
		service.deleteContact(authorization,contactId,body.expectedVersion(),key); return ResponseEntity.noContent().build();
	}
	@GetMapping("/settings")
	@Operation(operationId="U11",summary="U11 · 사용자 설정 조회",tags="6. 사용자 설정")
	public SettingView settings(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization) {
		return service.settings(authorization);
	}
	@PatchMapping(value="/settings",consumes="application/json")
	@Operation(operationId="U12",summary="U12 · 가상 수신 알림 방식 변경",tags="6. 사용자 설정")
	public SettingView updateSettings(@Parameter(hidden=true) @CookieValue(value="__Host-safecall-session",required=false) String authorization,
		@Valid @RequestBody SettingRequest body,@RequestHeader("Idempotency-Key") UUID key) { return service.updateSettings(authorization,body,key); }
}

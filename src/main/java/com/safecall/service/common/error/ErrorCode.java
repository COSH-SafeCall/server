package com.safecall.service.common.error;

public enum ErrorCode {
	ORIGIN_NOT_ALLOWED(403,"허용되지 않은 요청 출처입니다."),
	CSRF_INVALID(403,"요청 출처 또는 CSRF 토큰을 확인해 주세요."),
	ALREADY_AUTHENTICATED(409,"이미 로그인되어 있습니다."),
	REAUTHENTICATION_REQUIRED(403,"민감 작업을 위해 다시 인증해 주세요."),
	REAUTH_ACCOUNT_MISMATCH(403,"같은 카카오 계정으로 인증해 주세요."),
	INVALID_CONSENT(422,"동의 코드와 고정 발행 버전을 확인해 주세요."),
	DOCUMENT_NOT_READY(503,"문서가 아직 발행되지 않았습니다."),
	PROFILE_REQUIRED(422,"이름과 전화번호를 확인해 주세요."),
	MESSAGE_PROFILE_REQUIRED(409,"이름과 전화번호를 확인해 주세요."),
	CONTACT_REQUIRED(409,"비상 연락망을 등록해 주세요."),
	CONTACT_PHONE_CONFLICT(409,"본인 또는 다른 보호자와 같은 번호입니다."),
	INVALID_ALERT_MODE(422,"지원하지 않는 알림 모드입니다."),
	BUSINESS_VALIDATION_FAILED(422,"입력값을 확인해 주세요."),
	STALE_CONNECTION_GENERATION(409,"이전 연결 세대입니다."),
	RESUME_BUDGET_EXHAUSTED(409,"통화 재개 횟수를 모두 사용했습니다."),
	RENEWAL_ALREADY_REQUESTED(409,"이미 연결 재개를 요청했습니다."),
	GEMINI_VALIDATION_REQUIRED(503,"Gemini 연결 설정 검수가 필요합니다."),
	ONBOARDING_REQUIRED(403, "가입 안내를 완료해 주세요."),
	MICROPHONE_REQUIRED(403, "마이크 권한이 필요합니다."),
	CALL_ALREADY_OPEN(409, "이미 진행 중인 통화가 있습니다."),
	INVALID_CALL_OPTION(422, "통화 선택지를 확인해 주세요."),
	INVALID_QUICK_START(422, "빠른 시작 상대는 아빠여야 합니다."),
	INVALID_RECONNECT_SOURCE(422, "새 연결을 시작할 수 없는 이전 통화입니다."),
	PROMPT_NOT_READY(503, "통화 준비가 완료되지 않았습니다."),
	CALL_TERMINAL(409, "이미 종료된 통화입니다."),
	CALL_TRANSITION_INVALID(409, "현재 통화 상태에서 처리할 수 없는 사건입니다."),
	CONNECTION_ALREADY_USED(409, "이미 사용된 연결 정보입니다."),
	CONNECTION_GRANT_EXPIRED(410, "새 연결을 시작할 수 있는 시간이 지났습니다."),
	CONNECTION_ISSUE_UNKNOWN(503, "연결 정보 발급 결과를 확인할 수 없습니다. 새 통화를 시작해 주세요."),
	INVALID_REQUEST(400, "요청 형식이 올바르지 않습니다."),
	INVALID_CURSOR(400, "이용 기록 조회 위치가 올바르지 않습니다."),
	VALIDATION_FAILED(400, "입력값이 올바르지 않습니다."),
	AUTHENTICATION_REQUIRED(401, "인증이 필요합니다."),
	SESSION_EXPIRED(401, "세션이 만료되었습니다. 다시 로그인해 주세요."),
	TOKEN_REUSED(401, "이미 사용된 인증 정보가 감지되어 세션이 종료되었습니다."),
	KAKAO_TOKEN_INVALID(401, "카카오 인증 정보가 유효하지 않습니다."),
	LOGIN_REQUIRED(403, "카카오 로그인이 필요합니다."),
	ACCESS_DENIED(403, "접근할 수 없습니다."),
	CONSENT_REQUIRED(403, "필수 이용 동의가 필요합니다."),
	RESOURCE_NOT_FOUND(404, "대상을 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다."),
	NOT_ACCEPTABLE(406, "JSON 응답을 요청해 주세요."),
	VERSION_CONFLICT(409, "정보가 변경되었습니다. 다시 조회해 주세요."),
	IDEMPOTENCY_CONFLICT(409, "같은 요청 키에 다른 내용이 전달되었습니다."),
	REQUEST_IN_PROGRESS(409, "요청을 처리 중입니다. 잠시 후 다시 시도해 주세요."),
	AUTH_REPLAY_EXPIRED(409, "인증 결과의 재조회 시간이 만료되었습니다."),
	REFRESH_REPLAY_EXPIRED(409, "인증 갱신 결과의 재조회 시간이 만료되었습니다."),
	ACCOUNT_DELETION_PENDING(409, "계정 삭제를 처리 중입니다."),
	ONBOARDING_STEP_MISMATCH(409, "현재 가입 단계와 요청 단계가 다릅니다."),
	PROFILE_INCOMPLETE(422, "이름과 전화번호를 확인해 주세요."),
	INVALID_PHONE(422, "올바른 국내 휴대폰 번호를 입력해 주세요."),
	INVALID_BIRTH_DATE(422, "올바른 생년월일을 입력해 주세요."),
	CONTACT_NOT_FOUND(404, "보호자 정보를 찾을 수 없습니다."),
	CONTACT_LIMIT_REACHED(409, "보호자는 최대 2명까지 등록할 수 있습니다."),
	CONTACT_PHONE_DUPLICATE(409, "본인 또는 다른 보호자와 같은 번호는 등록할 수 없습니다."),
	DOCUMENT_CODE_INVALID(422, "지원하지 않는 문서 코드입니다."),
	NOT_A_CONSENT_DOCUMENT(422, "동의 대상 문서가 아닙니다."),
	CONSENT_VERSION_CHANGED(409, "동의 문서가 변경되었습니다. 최신 문서를 확인해 주세요."),
	WITHDRAWAL_REQUIRED(409, "동의 철회 기능으로 관련 데이터 정리를 요청해 주세요."),
	DATA_CLEANUP_PENDING(409, "관련 데이터 정리를 처리 중입니다."),
	DELETION_RECEIPT_EXPIRED(409, "삭제 접수증 재조회 시간이 만료되었습니다."),
	SETTINGS_SAVE_FAILED(503, "설정을 저장하지 못했습니다. 다시 시도해 주세요."),
	UNSUPPORTED_MEDIA_TYPE(415, "JSON 형식으로 요청해 주세요."),
	RATE_LIMITED(429, "요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."),
	INTERNAL_SERVER_ERROR(500, "요청을 처리하지 못했습니다."),
	KAKAO_UNAVAILABLE(503, "카카오 인증을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
	LOGOUT_FAILED(503, "로그아웃을 완료하지 못했습니다. 다시 시도해 주세요.");

	private final int status;
	private final String message;
	ErrorCode(int status, String message) { this.status = status; this.message = message; }
	public String code() {
		return switch (this) {
			case BUSINESS_VALIDATION_FAILED -> "VALIDATION_FAILED";
			case MESSAGE_PROFILE_REQUIRED -> "PROFILE_REQUIRED";
			default -> name();
		};
	}
	public int status() { return status; }
	public String message() { return message; }
}

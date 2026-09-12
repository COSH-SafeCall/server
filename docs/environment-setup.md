# 환경 설정

[문서 목록](README.md) · v4.2-web-mvp

[.env.example](../.env.example)은 **32개 항목의 값이 비어 있는 양식**이다. 값을 생성하거나 실행 설정을 자동 적용하지 않는다. 설정 파일과 실행 환경변수를 사용하며, `local`은 로컬 `.env`를 추가로 읽는다. `prod`는 `.env`를 가져오지 않는다.

## 설정 순서

1. Java 21, MySQL 8.0.41 이상 8.0/8.4를 준비한다. 테스트를 실행할 경우 Python 3도 필요하다.
2. 아래 표를 참고하여 로컬 `.env`에 필요한 값을 직접 입력한다. 기존 `.env`가 있으면 전체 파일을 덮어쓰지 않는다.
3. 기본값을 사용할 선택 항목은 `.env`에서 그 줄을 제거한다. `KEY=`는 빈 값이며 `${KEY:기본값}`의 기본값 선택과 다르다. 숫자·URL 항목을 빈 줄로 남기면 시작에 실패할 수 있다.
4. [DB 설치](local-db-setup.md)와 필요한 문서 발행을 마친 뒤 `./gradlew.bat bootRun`을 실행한다.

## DB·웹·암호화

표의 기본값은 설정 항목을 **생략했을 때** 적용되는 local 기준이다.

| 항목 | 용도 / 기본값 |
|---|---|
| DB_HOST / DB_PORT / DB_NAME | DB 주소 / `127.0.0.1`, `3306`, `safecall` |
| DB_USERNAME / DB_PASSWORD | 전용 DB 계정 / `safecall_app`, 비밀번호는 환경에 맞춰 입력 |
| SERVER_PORT | 서버 포트 / `8081` |
| SWAGGER_ENABLED | local Swagger 공개 여부 / `true` |
| WEB_ORIGIN | 페이지와 API의 정확한 origin / `http://localhost:8081` |
| CSRF_SECRET / HMAC_SECRET / RESPONSE_ENCRYPTION_SECRET | 서로 다른 난수 32바이트를 Base64로 인코딩한 값. 모두 필수 |
| CRYPTO_KEY_DIRECTORY | local DB 외부 키 디렉터리 / `.keys` |

암호화 키는 기존 데이터의 복호화·식별에 사용한다. 기존 키를 임의로 새 값으로 바꾸지 않는다. 키 디렉터리 접근 권한은 실행 계정으로 제한한다. prod의 외부 키 저장소 조건은 [배포 문서](aws-deployment-readiness.md)를 따른다.

## 카카오 OAuth

| 항목 | 용도 / 기본값 |
|---|---|
| KAKAO_APP_ID | 숫자 앱 ID / `0`은 실제 앱 설정 전 상태 |
| KAKAO_CLIENT_ID | REST API 키 / 미설정 시 OAuth 시작 불가 |
| KAKAO_CLIENT_SECRET | 앱에서 활성화한 client secret / 미사용 시 생략 가능 |
| KAKAO_REDIRECT_URI | 정확히 `WEB_ORIGIN` + `/api/v1/auth/kakao/callback` |
| KAKAO_LOGIN_SCOPES | 앱 승인 범위에 맞춘 항목 / `name,gender,birthday,birthyear,phone_number` |
| AUTH_REQUESTS_PER_MINUTE | 인증 시작 IP별 분당 제한 / `10` |
| AUTH_CLEANUP_DELAY_MS | 인증·삭제·보존 정리 주기(ms) / `1000` |

origin을 바꾸면 redirect URI도 함께 설정한다. 서버 포트만 바꾸어도 WEB_ORIGIN이 자동 변경되는 것은 아니다. **WEB_ORIGIN은 허용 출처 설정이며 HTTPS 리스너나 인증서를 구성하지 않는다.** 현재 기본 서버는 HTTP로 실행되므로 HTTPS 주소를 사용하려면 해당 주소에서 TLS를 처리할 개발 프록시 등의 구성이 따로 필요하다. 브라우저·콘솔 등록은 [카카오 설정](kakao-token-test-setup.md)을 참고한다.

## Gemini·통화

| 항목 | 용도 / 기본값 |
|---|---|
| GEMINI_API_KEY | 서버 전용 provider 키 / 미설정 시 새 통화 불가 |
| GEMINI_EPHEMERAL_TOKEN_URL | 허용된 발급 URL / `https://generativelanguage.googleapis.com/v1beta/auth_tokens` |
| GEMINI_VALIDATED_MODEL | 검수한 모델 ID. 발행 프롬프트의 모델과 같아야 함 |
| GEMINI_VALIDATION_REF | 실제 모델/API/음성/재개 검수 자료의 참조 |
| GEMINI_MODEL_MAX_SECONDS | 검수된 모델의 통화 상한(초) / `0`은 미검수 상태 |
| SAFECALL_GEMINI_CONNECTION_TTL_SECONDS | 서버 통화 상한(초) / `600`, 허용 1~600 |
| SAFECALL_GEMINI_NEW_SESSION_TTL_SECONDS | 새 연결 시작 기한(초) / `60`, 허용 1~60이며 connection TTL 이하여야 함 |
| CALL_POLICY_VERSION | 통화에 저장할 정책 버전 / `mvp-2026-09-11` |
| CALL_MAX_RESUME_ATTEMPTS | 같은 통화 전체 재개 예산 / `1` |
| CALL_RESUME_DELAY_MS | 재개 대기(ms) / `1000` |
| CALL_LEASE_SECONDS | heartbeat lease(초) / `30`, 5보다 커야 함 |
| CALL_ISSUE_TIMEOUT_SECONDS | ISSUING 결과 불명 판정 기한(초) / `10`, 1 이상이며 lease 미만. grant 생성 시각 기준 |
| CALL_WORKER_DELAY_MS | 통화 작업자 실행 주기(ms) / `1000` |

모델/API/voice는 발행된 promptRelease/personaPrompt에서 읽는다. 환경변수만 입력하거나 DRAFT 초안을 넣는 것으로 통화가 준비되지는 않는다. 발행 경로는 [명세·코드 대응](ai-safety-call-reference-review.md)을 참고한다.

예를 들어 connection TTL을 30초로 줄이면 new-session TTL도 30초 이하로 맞춰야 한다. 기본 60초를 그대로 두면 시작 시 설정 검증에 실패한다. 통화의 최종 만료는 서버 상한·검수된 모델 상한·웹 세션 잔여 시간의 최솟값이다.

## 시작 문제 확인

| 증상 | 먼저 확인할 항목 |
|---|---|
| 숫자·URL 변환 또는 정책 초기화 실패 | 값이 빈 항목, 포트/origin/callback 불일치, 허용 범위 |
| 암호화 설정 실패 | Base64로 인코딩한 32바이트 키 3개 및 키 간 중복 |
| DB 연결 실패 | DB 설치 여부, 전용 계정, 호스트/포트/권한 |
| 세션 쿠키가 브라우저에 남지 않음 | [공통 요청 가이드](swagger-test-guide.md)의 HTTPS·origin 조건 |
| 통화 시작 503 | provider 키, PUBLISHED 프롬프트, 모델 검수 설정 |
| prod 시작 실패 | [외부 UserKeyStore 및 배포 조건](aws-deployment-readiness.md) |

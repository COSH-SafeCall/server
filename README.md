# SafeCall 서버

Java 21 · Spring Boot 4.1 · MySQL 8.0.41 이상. `reference`와 `design`의 **v4.2-web-mvp / 2026-09-12** 최종 문서를 기준으로 공통 설계 및 API 1~5장까지 구현한다. A02의 시작/콜백을 별개로 세어 총 29개 HTTP 작업이다. A03 refresh는 제거되었다.

## 변경 내용과 구조

설정·API 검증·배포 안내는 [문서 목록](docs/README.md), 변경 전후 비교는 [리팩토링 리포트](docs/web-v4-refactor-report.md)를 참고한다.

| 영역 | 코드 | 현재 동작 |
|---|---|---|
| 인증 | `auth/api`, `auth/service`, `auth/kakao` | 보안 쿠키, 세션별 CSRF, OAuth code 교환, LOGIN/REAUTH 분리 |
| 사용자 | `user/api`, `user/service`, `user/repository` | 프로필 PATCH, 문서 단건 조회, 동의·철회, 연락망·설정 |
| 홈 | `home` | 열린 통화와 정리 상태를 반영한 기능 자격, 고정 상황·상대 목록 |
| 통화 | `call` | 페이지 키 소유권, 단기 grant, 상태 전이, heartbeat, 같은 통화 재개 1회 |
| 메시지 | `message` | 클릭 시 자격·최신 보호자 재검증, 본인 번호 마스킹, SAFETY/TEST 작성 자료 |
| 공통 | `common` | 엄격한 JSON, 오류 응답, HMAC/AES-GCM, 외부 키, Origin/CORS/CSP |
| DB | `db/schema-mysql.sql` | 최종 DDL과 동일한 19개 테이블, 189개 컬럼, 171개 제약 |

JPA Entity 대신 JDBC repository와 행 record를 사용한다. UUID는 swap 없는 BINARY(16), 시간은 UTC DATETIME(6), 개인정보는 Cipher/Hash/외부 keyRef로 저장한다. 음성·대화·완성 프롬프트·좌표·메시지·재개 handle은 저장하지 않는다.

## 구현 API

모든 경로의 접두사는 `/api/v1`이다.

| ID | 메서드·경로 |
|---|---|
| A01 | POST `/auth/guest` |
| A02 | POST `/auth/kakao/authorization`, GET `/auth/kakao/callback` |
| A04 | POST `/auth/logout` |
| A05 | GET `/auth/session` |
| A06 / A07 | GET `/onboarding`, POST `/onboarding/advance` |
| U01 / U02 | GET / PATCH `/me/profile` |
| U03 | GET `/documents/{code}?version=...` |
| U04 / U05 | GET / POST `/me/consents` |
| U06 | POST `/me/consents/{code}/withdrawal` |
| U07 / U08 | GET / POST `/me/emergency-contacts` |
| U09 / U10 | PATCH / DELETE `/me/emergency-contacts/{contactId}` |
| U11 / U12 | GET / PATCH `/me/settings` |
| H01 / H02 | GET `/home`, GET `/call-options` |
| C01 / C02 | POST `/calls`, GET `/calls/{callId}` |
| C03 / C04 | GET `/calls/{callId}/connection`, POST `/calls/{callId}/events` |
| C05 / C06 | POST `/calls/{callId}/heartbeat`, POST `/calls/{callId}/end` |
| C07 | POST `/calls/{callId}/connection-renewals` |
| M01 | GET `/message-composer?mode=SAFETY` (또는 TEST) |

## 로컬 실행

1. [DB 설정](docs/local-db-setup.md)에 따라 **새 빈 DB용** `db/schema-mysql.sql`을 적용한다. 기존 DB에 대한 ALTER/데이터 이관 스크립트는 아니다.
2. [환경 설정](docs/environment-setup.md)에 따라 로컬 `.env`를 준비한다. `.env.example`은 값이 모두 빈 양식이며 기본값을 사용할 선택 항목은 해당 줄을 제거한다. 독립적인 CSRF/HMAC/응답 암호화 키 3개가 필요하다.
3. `WEB_ORIGIN`과 `KAKAO_REDIRECT_URI`를 실제 브라우저 주소에 맞추고 카카오 REST API 앱 정보를 설정한다. [OAuth 설정](docs/kakao-token-test-setup.md)을 참고한다.
4. 검수된 문서 3종과 안내 문서를 발행한다. 동의 문서의 최초 버전과 isCurrent는 프로젝트 기간 동안 고정한다. 샘플 프롬프트는 DRAFT이므로 통화를 활성화하지 않는다.
5. `./gradlew.bat bootRun`으로 시작한다. Swagger는 기본 `/swagger-ui/index.html`, OpenAPI는 `/v3/api-docs`이다. 브라우저 호출 방법은 [웹 API 검증 가이드](docs/swagger-test-guide.md)를 참고한다.

통화를 활성화하려면 실제 검수한 모델/API/음성/재개 조합의 promptRelease와 `GEMINI_VALIDATED_MODEL`, `GEMINI_VALIDATION_REF`, `GEMINI_MODEL_MAX_SECONDS`, `GEMINI_API_KEY`를 설정해야 한다. 임의 검수 참조로 이 조건을 통과시켜서는 안 된다.

## 검증

```powershell
./gradlew.bat test bootJar
python scripts/verify_auth.py
python scripts/report_web_verification.py
```

명령별 검증 범위와 최신 기록은 [자동 검증 문서](docs/verification.md)를 참고한다. 통합 테스트는 서비스 DB와 실제 외부 API를 사용하지 않으며 로컬 `.env`도 읽지 않는다.

## 정책 및 범위

세션은 ANONYMOUS 10분, GUEST 24시간, KAKAO 고정 14일이다. 명시적 동일 계정 REAUTH의 민감 작업 허용 시간은 5분이다. 알려진 카카오 인앱 브라우저에서는 REAUTH와 개인정보 처리 동의 철회를 거절한다. 브라우저 지원 여부에 대한 실제 기기 검수는 별도로 필요하다.

통화 상한은 600초/검수된 모델 상한/세션 잔여 시간의 최솟값이다. lease 30초, heartbeat 5초, 재개 최대 1회, 대기 1초를 적용한다. 통화 생성 시 정책을 고정한다. ISSUING 결과 불명은 생성 후 10초 기준으로 정리하며 같은 grant를 외부에 재발급하지 않는다.

AI/위치 철회는 관련 데이터와 완료 상태를 원자적으로 정리한다. ACCOUNT는 접수증 재생 60초 후 로컬 삭제와 LOCAL_DELETED를 같은 트랜잭션으로 커밋한다. 외부 카카오 연결 해제와 삭제 상태 조회 R03 등 6장 API는 후속 범위이므로 ACCOUNT를 COMPLETED로 표시하지 않는다. 24시간 미완료 계정도 같은 정리 경로를 사용한다.

비회원 세션은 만료/폐기 1시간 후 정리 대상이 되며, 회원 세션은 폐기/만료 후 30일, 종료 통화 메타데이터는 종료 후 30일, 운영 이벤트는 14일, 완료 삭제 작업은 완료 후 30일 기준으로 정리한다. 더 이른 계정/세션/동의 삭제는 FK cascade를 따른다.

M01은 작성 버튼 클릭 시 현재 회원 자격·보호자를 한 SQL 스냅샷으로 재검증하며 별도 메시지 행을 저장하지 않는다. SAFETY는 COMPLETE, TEST는 MESSAGE_TEST 또는 COMPLETE 단계에서 사용한다. 작성 자료는 no-store이며 브라우저 메모리에서 최대 5분 또는 세션 만료까지 유지한다. 미검수 지도 설정은 mapTemplate=null이다. 실제 좌표·완성 URL·본문 조립은 브라우저가 담당한다. [메시지 API 검증](docs/safety-message-swagger-test.md)을 참고한다.

이력·삭제 조회·telemetry 등 6~7장 신규 API는 구현 범위 밖이다. 운영 외부 키 저장소는 미구현이므로 `prod` 시작은 계속 차단한다. 실제 카카오/Gemini 연동, 브라우저 음성·재개 및 운영 배포는 자동 테스트 완료와 별개다.

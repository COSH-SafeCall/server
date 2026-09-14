# 서비스 구조

[문서 목록](README.md) · Java 21 / Spring Boot 4.1 / MySQL / JDBC

## 코드 구성

기본 패키지는 `src/main/java/com/safecall/service`다. Controller가 HTTP와 DTO를 처리하고 Service가 권한·상태·트랜잭션을 관리하며 Repository가 SQL과 행 매핑을 담당한다. JPA Entity 대신 JDBC와 Java record를 사용한다.

| 영역 | 주요 코드 | 역할 |
|---|---|---|
| 인증 | [OAuthService](../src/main/java/com/safecall/service/auth/service/OAuthService.java), [AuthTransactions](../src/main/java/com/safecall/service/auth/service/AuthTransactions.java) | 세션 쿠키, OAuth code 교환, LOGIN/REAUTH, 온보딩 |
| 사용자 | [UserTransactions](../src/main/java/com/safecall/service/user/service/UserTransactions.java) | 프로필·동의·연락망·설정과 version 검사 |
| 홈 | [HomeService](../src/main/java/com/safecall/service/home/service/HomeService.java) | 기능 자격과 통화 선택지 |
| 통화 | [CallService](../src/main/java/com/safecall/service/call/service/CallService.java), [CallWorker](../src/main/java/com/safecall/service/call/service/CallWorker.java) | 생성·상태 전이·발급·만료·재개 |
| 메시지 | [MessageService](../src/main/java/com/safecall/service/message/service/MessageService.java) | 클릭 시 작성 자격과 최신 수신자 재검증 |
| 이력·삭제 | [HistoryService](../src/main/java/com/safecall/service/history/service/HistoryService.java), [AccountExternalCleanup](../src/main/java/com/safecall/service/history/service/AccountExternalCleanup.java) | 이력 커서, 삭제 접수·상태, 외부 정리 |
| 운영 사건 | [TelemetryService](../src/main/java/com/safecall/service/telemetry/service/TelemetryService.java) | 허용 사건·중복·한도·배치 원자성 |
| 보안·키 | [RequestFilter](../src/main/java/com/safecall/service/common/config/RequestFilter.java), [TransientKeys](../src/main/java/com/safecall/service/common/crypto/TransientKeys.java) | Origin/CSRF, 엄격한 입력, 암호화·키 수명 |

## 인증과 데이터

세션은 Secure/HttpOnly 쿠키, 변경 요청은 세션별 CSRF로 보호한다. OAuth는 시작 세션에 묶인 state를 한 번 선점하여 code를 교환하고 결과 저장 전에 세션·계정을 재검증한다. 일반 LOGIN과 같은 계정 REAUTH를 구분한다. 카카오 토큰 교환은 본문 수신까지 최대 5초 대기하고 응답은 65,536바이트로 제한한다. 이는 OAuth 전체 흐름의 총 제한 시간이 아니다.

UUID는 swap 없는 BINARY(16), 시간은 UTC DATETIME(6)로 저장한다. 개인정보는 암호문·용도별 HMAC·DB 외부 keyRef로 관리한다. 음성·대화·완성 프롬프트·좌표·메시지 본문·재개 handle은 저장하지 않는다. 핵심 변경 경로의 잠금 순서는 계정 → 세션 → 통화 → grant다. OAuth/Gemini 네트워크 요청은 선점 트랜잭션과 결과 저장 트랜잭션 사이에서 수행한다.

## 통화·메시지

- 통화 소유권은 세션과 현재 페이지 키를 함께 검사한다. 생성 시 정책을 고정하며 기본 lease는 30초, heartbeat는 5초, 총 상한은 600초·검수 모델 상한·세션 잔여 시간 중 최솟값이다.
- PENDING → ISSUING 선점 시 `issuingStartedAt`을 기록한다. 기본 10초의 발급 제한은 이 시각부터 계산한다. 결과가 불명확한 grant는 UNKNOWN으로 종료하고 외부에 재발급하지 않는다.
- 같은 통화 재개는 성공·실패를 합쳐 기본 1회다. 최초 발급 시각으로 현재 허용된 프로필과 고정 release의 지침을 재구성하고 최초 HMAC과 같을 때만 발급한다. 불일치·검증 정보 누락 시 RESUMPTION_FAILED다. 재개 handle은 브라우저 메모리에 둔다.
- 게스트 통화 한도는 세션 한도 외에도 접속 IP·KST 날짜의 HMAC 버킷으로 검사한다. 클라이언트의 Forwarded 헤더를 직접 신뢰하지 않는다.
- M01은 계정·세션 잠금 아래 단일 조회로 프로필·동의·보호자·정리·열린 통화를 읽는다. 현재 작성 자료만 반환하며 SMS를 발송하지 않는다. H01도 같은 작성 자격을 반영한다. USAGE_HISTORY 정리는 메시지 작성 차단 범위에서 제외한다.
- O01은 사건을 기록할 뿐 통화·인증 상태를 변경하지 않는다. AUTH_SUCCEEDED는 새 GUEST/KAKAO 세션 생성과 함께 서버가 기록한다.

## 작업자와 키 수명

[SchedulingConfiguration](../src/main/java/com/safecall/service/common/config/SchedulingConfiguration.java)은 통화 배정·만료용 `callScheduler` 1개 스레드와 정리용 `cleanupScheduler` 2개 스레드를 분리한다. 실제 Gemini 발급은 최대 4개 issuer 스레드에서 수행한다. 이 구성은 스케줄러 점유를 격리하며 DB 잠금·전체 부하까지 격리하지는 않는다.

키 생성 전 [KeyCreationJournal](../src/main/java/com/safecall/service/common/crypto/KeyCreationJournal.java)이 별도 최대 2개 DB 연결로 복구 의도를 커밋한다. 업무 트랜잭션이 그 행을 잠근 뒤 키를 생성하며, 커밋하면 의도가 제거되고 롤백하면 정리할 참조가 남는다. DB 연결 예산은 업무 풀 최대치 + 2개다.

기존 키의 참조 제거와 `keyDiscardJob` 추가는 같은 트랜잭션에서 커밋한다. [KeyDiscardQueue](../src/main/java/com/safecall/service/common/crypto/KeyDiscardQueue.java)가 정리 스케줄러에서 기본 1초 간격으로 조회하여 폐기하고, 실패는 30초 뒤 재시도한다. 1초는 완료 보장이 아니다. 미완료 작업은 TTL로 지우지 않으며 이미 폐기된 키의 재시도도 성공해야 한다. 결과 불명 시 키를 먼저 없애지 않는다.

ACCOUNT는 60초 접수증 재생 구간 뒤 로컬 삭제와 LOCAL_DELETED를 원자 커밋한다. 별도 암호화 작업으로 개인 키 폐기·카카오 연결 해제를 수행한 뒤 COMPLETED로 바꾼다. `externalNextAttemptAt`과 암호화된 60초 lease로 재시도 대상을 선점하고, 늦은 이전 시도가 새 결과를 덮어쓰지 못하게 한다. 키 조회·복호화 실패도 다음 시도로 미뤄 뒤의 작업이 진행되게 한다. 로컬 커밋 뒤 외부 오류를 FAILED로 표시하지 않는다.

## 기본 보존 정책

| 대상 | 기간·기준 |
|---|---|
| 익명 / 게스트 / 회원 인증 | 10분 / 24시간 / 고정 14일 |
| 민감 작업 재인증 | 동일 계정 REAUTH 후 5분 |
| 미완료 가입 | 24시간 후 ACCOUNT 정리 |
| 비회원 / 회원 세션 정리 | 만료·폐기 후 1시간 / 30일 |
| 종료 통화 / 운영 사건 / 완료 삭제 작업 | 종료 후 30일 / recordedAt부터 14일 / 완료 후 30일 |

동의·계정·세션 삭제로 더 일찍 정리될 수 있다. 실제 보존·복원 조건은 [운영 정책](../../design/최종_운영_정책.md)을 따른다.

## API 목록

Swagger 그룹과 API 표시 순서는 [OpenApiDisplayOrder](../src/main/java/com/safecall/service/common/config/OpenApiDisplayOrder.java)에서 관리한다. [SwaggerUiConfiguration](../src/main/java/com/safecall/service/common/config/SwaggerUiConfiguration.java)이 이 순서를 사용하는 화면 정렬 함수를 제공한다. API를 추가할 때 해당 그룹의 operationId 목록도 갱신한다.

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
| R01 | GET `/me/usage-history?limit=20&cursor=...` |
| R02 | POST `/me/data-deletions` |
| R03 | GET `/data-deletions/{jobId}` |
| O01 | POST `/telemetry/events` |

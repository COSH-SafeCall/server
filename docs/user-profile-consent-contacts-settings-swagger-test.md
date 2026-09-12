# 사용자·문서·동의·홈 검증 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [공통 요청](swagger-test-guide.md), [웹 인증](auth-session-onboarding-swagger-test.md)

경로의 `/api/v1` 접두사는 생략한다. **U03은 로그인 없이 공개 조회**할 수 있다. 나머지 사용자 API는 회원 세션을 사용하고 모든 변경 요청에 현재 CSRF와 UUID Idempotency-Key를 전달한다.

## 사용자 API

| API | 입력과 확인 사항 |
|---|---|
| U01 GET `/me/profile` | 회원 정보와 출처/버전. 현재 AI 동의가 없으면 성별·생일을 마스킹한다. |
| U02 PATCH `/me/profile` | name, gender, birthDate, phone, isConfirmed=true, expectedVersion 모두 필요. gender/birthDate는 명시적 null 가능. |
| U03 GET `/documents/{code}` | 공개 단건 조회. version 생략은 현재 버전, 지정은 해당 버전. ETag를 If-None-Match로 보내면 304. |
| U04 GET `/me/consents` | decisionVersion/currentVersion, action, isEffective 확인. |
| U05 POST `/me/consents` | decisions 1~3개. GRANTED/DECLINED만 입력하고 중복 코드·임의 버전은 거절. |
| U06 POST `/me/consents/{code}/withdrawal` | 본문 `{}`. 202 DeletionView 7필드와 HttpOnly 접수증 쿠키. receiptToken JSON 없음. |
| U07/U08 `/me/emergency-contacts` | 등록은 name/relationship/phone. 최대 2명이며 본인/다른 보호자 번호 중복 금지. |
| U09 PATCH `/me/emergency-contacts/{id}` | name/relationship/phone/expectedVersion 필수. |
| U10 DELETE `/me/emergency-contacts/{id}` | 본문 `{"expectedVersion":현재버전}`, 204. |
| U11/U12 `/me/settings` | PATCH에 incomingAlertMode(RINGTONE/SILENT), expectedVersion. 잘못된 모드는 422 INVALID_ALERT_MODE. |

예시 U02:

```json
{"name":"홍길동","gender":null,"birthDate":null,"phone":"+82 10-1234-5678","isConfirmed":true,"expectedVersion":1}
```

이름 공백·길이, 실제 달력 날짜, 한국 전화번호 정규화, 다른 회원 리소스 404, 오래된 version 409를 확인한다. `+82 10-1234-5678`은 `01012345678`로 저장/응답한다. 새로운 성별·생일 저장에는 현재 AI 동의가 필요하다.

version=1은 예시다. U01/U07/U11에서 실제 버전을 읽은 뒤 PATCH/DELETE에 사용한다. U08은 201, U10은 본문 없는 204, U02/U09/U12는 현재 리소스를 반환한다.

## 동의 철회

AI_CALL 철회 접수는 통화를 즉시 종료하고 AI_DATA 작업을 생성한다. 이력/성별/생일의 물리 삭제는 정리 작업자가 COMPLETED 전환과 함께 원자적으로 처리하며 이름·전화번호·연락망은 유지한다. LOCATION_PROCESSING 철회는 위치 관련 정리만 수행한다. 개인정보 처리 동의 철회는 최근 REAUTH가 필요하고 ACCOUNT 작업을 생성한다. 정리 중에는 해당 범위의 재동의/생성을 차단한다. 관련 없는 다른 선택 동의까지 차단하지 않는다.

U06의 202는 접수 성공이므로 데이터 삭제 완료로 판정하지 않는다. 현재 GRANTED인 동의를 U05의 DECLINED로 바꾸려 하면 WITHDRAWAL_REQUIRED이며 U06을 사용해야 한다.

같은 U06 키와 본문은 60초간 동일 접수증 쿠키와 현재 작업 상태를 재생한다. ACCOUNT 로컬 삭제는 그 뒤 시작하며 LOCAL_DELETED와 데이터 삭제를 함께 커밋한다. 외부 연결 정리와 6장 조회 API는 후속 범위다. DB 실패 테스트에서는 데이터와 키가 유지되어야 하며 불확실한 커밋을 FAILED로 단정하지 않는다.

DeletionView 필드는 `id`, `scope`, `status`, `requestedAt`, `dueAt`, `completedAt`, `errorCode`다. `dueAt`은 목표 기한이며 완료 증명이 아니다. 접수증 token은 JSON에서 찾거나 별도 저장하지 않는다.

## 홈과 선택지

| API / 시나리오 | 확인할 응답 |
|---|---|
| H01 GET `/home`, 게스트 | 작성 불가, LOGIN_REQUIRED, guardianCount=0, settingsMode=LOGIN_ONLY |
| H01, 회원 | 온보딩·프로필·현재 개인정보 동의·연락망·열린 통화·정리 상태에 따른 messageBlockReasons |
| H01, AI 동의 없음 | AI 동의만으로 메시지 작성 자격을 제한하지 않음. 진행 중 AI 정리는 별도 차단 사유 |
| H02 GET `/call-options` | 상황 4개, 상대 3개, 빠른 시작 FATHER/1000ms, catalogVersion=3 |
| H02, If-None-Match 재조회 | 인증 후 같은 ETag면 304. 익명은 캐시 태그가 있어도 조회 불가 |

H01은 기능 자격만 반환하며 실제 메시지 본문·위치 자료를 미리 제공하지 않는다. M01 작성 API는 후속 범위다. ACCOUNT 삭제가 진행 중이면 세션 접근 단계에서 ACCOUNT_DELETION_PENDING으로 거절될 수 있다.

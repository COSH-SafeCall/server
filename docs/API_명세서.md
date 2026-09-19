# SafeCall Web MVP API 명세서

이 문서는 현재 `server/src/main/java` 구현을 기준으로 한 API 계약의 정본이다. 기준 버전은 `4.2-web-mvp`이며 기본 경로는 `/api/v1`이다.

## 1. 공통 계약

### 1.1 전송 형식

- 요청과 응답의 날짜·시각은 시간대가 포함된 RFC 3339 문자열이다.
- JSON 요청은 알 수 없는 필드, 중복 키, 뒤따르는 추가 JSON 값을 허용하지 않는다.
- 숫자·불리언·enum에 문자열을 대신 보내는 묵시적 형 변환은 허용하지 않는다.
- 본문이 있는 요청은 `Content-Type: application/json`을 사용한다.
- `Accept`가 서버의 JSON 응답을 허용하지 않으면 `406 NOT_ACCEPTABLE`이다.
- 모든 API 응답에는 `X-Request-Id`, `Cache-Control: no-store` 및 보안 헤더가 포함된다. H02의 캐시 정책은 별도 명시한 값을 우선한다.

### 1.2 인증과 보안 헤더

| 값 | 규칙 |
|---|---|
| `__Host-safecall-session` | 서버가 발급하는 Secure·HttpOnly·SameSite=Lax 세션 쿠키 |
| `Origin` | 변경 요청은 설정된 `WEB_ORIGIN`과 정확히 같아야 함 |
| `X-CSRF-Token` | `POST`, `PATCH`, `DELETE`에 A05 응답의 현재 토큰 사용. 세션 쿠키가 없는 A04만 예외 |
| `Idempotency-Key` | UUID 문자열. U02/U05/U08/U09/U10/U12, C01/C04/C06, R02에 필수 |
| `X-Call-Page-Key` | C01~C06에 필수. 32바이트 난수의 패딩 없는 43자 Base64URL 문자열이며 같은 통화 페이지에서 유지 |

새 논리 작업에는 새 `Idempotency-Key`를 사용하고, 네트워크 오류로 같은 요청을 재전송할 때만 같은 키와 같은 본문을 사용한다. 같은 키에 다른 본문을 보내면 `409 IDEMPOTENCY_CONFLICT`이다.

### 1.3 공통 오류 JSON

업무 오류와 요청 형식 오류는 다음 구조로 반환한다.

```json
{
  "timestamp": "2026-09-18T12:34:56.123456Z",
  "status": 409,
  "code": "VERSION_CONFLICT",
  "message": "정보가 변경되었습니다. 다시 조회해 주세요.",
  "errors": [],
  "path": "/api/v1/me/settings"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `timestamp` | string | UTC, 마이크로초 6자리 |
| `status` | integer | HTTP 상태와 같은 값 |
| `code` | string | 프론트 분기용 고정 코드 |
| `message` | string | 사용자에게 전달 가능한 고정 한국어 메시지 |
| `errors` | array | Bean Validation 실패 필드 목록. 그 외에는 빈 배열 |
| `path` | string | 쿼리 문자열을 제외한 요청 URI |

Bean Validation 실패 예시는 다음과 같다. 거절된 실제 입력값은 반사하지 않는다.

```json
{
  "timestamp": "2026-09-18T12:34:56.123456Z",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "입력값이 올바르지 않습니다.",
  "errors": [
    {
      "field": "expectedVersion",
      "value": null,
      "reason": "입력값을 확인해 주세요."
    }
  ],
  "path": "/api/v1/me/settings"
}
```

`REQUEST_IN_PROGRESS`, `RATE_LIMITED`, `CALL_CAPACITY_REACHED`는 응답 헤더에 초 단위 `Retry-After`를 포함한다. DB 잠금 경쟁으로 발생한 `REQUEST_IN_PROGRESS`의 값은 1초이며, 전체 활성 통화 한도 초과인 `CALL_CAPACITY_REACHED`의 값은 10초다.

## 2. API 목록

### 2.1 인증·세션

| ID | 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|---|
| A05 | `GET /auth/session` | 없음 | `200 SessionView`; 유효 세션이 없으면 익명 세션 쿠키 발급 |
| A01 | `POST /auth/guest` | `{}` | `200 SessionView`; 게스트 세션 쿠키 발급 |
| A02 | `POST /auth/virtual` | `{}` | `200 SessionView`; 가상 회원과 회원 세션 쿠키 발급 |
| A04 | `POST /auth/logout` | `{}` | `204`, 본문 없음; 세션 쿠키 만료 |

`SessionView`:

```json
{
  "kind": "MEMBER",
  "isAuthenticated": true,
  "csrfToken": "서버가 발급한 토큰",
  "expiresAt": "2026-09-18T13:34:56.123456Z",
  "settingsMode": "MEMBER"
}
```

- `kind`: `ANONYMOUS`, `GUEST`, `MEMBER`
- `isAuthenticated`: `MEMBER`일 때만 `true`
- `settingsMode`: 사용자 ID가 없으면 `LOGIN_ONLY`, 있으면 `MEMBER`
- A01과 A02는 IP 기준 분당 인증 요청 한도를 적용한다.

### 2.2 홈·선택지

| ID | 메서드·경로 | 성공 응답 |
|---|---|---|
| H01 | `GET /home` | `200 HomeView` |
| H02 | `GET /call-options` | `200 CallOptionsView` 또는 `304` |

`HomeView`:

```json
{
  "isMessageComposeEligible": true,
  "messageBlockReasons": [],
  "isLocationPermissionGranted": false,
  "guardianCount": 1,
  "settingsMode": "MEMBER"
}
```

`messageBlockReasons`에는 `LOGIN_REQUIRED`, `PROFILE_REQUIRED`, `CALL_ALREADY_OPEN`, `DATA_CLEANUP_PENDING`, `CONTACT_REQUIRED`가 들어갈 수 있다. 게스트 H01은 오류가 아니라 `LOGIN_REQUIRED` 사유가 포함된 `200`을 반환한다.

`CallOptionsView`:

```json
{
  "scenarios": [
    {"code":"FOLLOWED","label":"누군가 따라오는 것 같아요","quickDirection":null}
  ],
  "counterparts": [
    {"code":"FATHER","label":"아빠","displayName":"아빠"}
  ],
  "quickStart": {"holdMs":1000,"counterpartCode":"FATHER"},
  "catalogVersion": 3
}
```

- 상황 코드: `FOLLOWED`, `UNSAFE_TAXI`, `STRANGER_NEARBY`, `WALKING_ALONE`
- 상대 코드: `FATHER`, `MOTHER`, `FRIEND`
- H02는 `ETag`와 `Cache-Control: no-cache, private`를 반환한다. 같은 `If-None-Match`를 보내면 인증 검증 후 `304`와 빈 본문을 반환할 수 있다.

### 2.3 사용자

| ID | 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|---|
| U01 | `GET /me/profile` | 없음 | `200 ProfileView` |
| U02 | `PATCH /me/profile` | `ProfileRequest` | `200 ProfileView` |
| U04 | `GET /me/permissions` | 없음 | `200 Items<PermissionView>` |
| U05 | `POST /me/permissions` | `PermissionsRequest` | `200 Items<PermissionView>` |
| U07 | `GET /me/emergency-contacts` | 없음 | `200 Items<ContactView>` |
| U08 | `POST /me/emergency-contacts` | `ContactRequest` | `201 ContactView`, `Location` 헤더 |
| U09 | `PATCH /me/emergency-contacts/{contactId}` | `ContactUpdate` | `200 ContactView` |
| U10 | `DELETE /me/emergency-contacts/{contactId}` | `DeleteContactRequest` | `204`, 본문 없음 |
| U11 | `GET /me/settings` | 없음 | `200 SettingView` |
| U12 | `PATCH /me/settings` | `SettingRequest` | `200 SettingView` |

U02 요청:

```json
{
  "name": "홍길동",
  "gender": "MALE",
  "birthDate": "2000-01-01",
  "phone": "01012345678",
  "isConfirmed": true,
  "expectedVersion": 1
}
```

- `name`: 필수, 공백 제외, 정규화 후 최대 50 코드포인트
- `gender`: `MALE`, `FEMALE` 또는 명시적 `null`
- `birthDate`: `YYYY-MM-DD` 또는 명시적 `null`; 미래 날짜 불가
- `phone`: 국내 휴대폰 번호. 공백·하이픈과 `+82` 표기는 정규화됨
- `isConfirmed`: 반드시 `true`
- `expectedVersion`: 1 이상의 최신 U01 버전

`ProfileView`:

```json
{
  "name": "홍길동",
  "gender": "MALE",
  "birthDate": "2000-01-01",
  "phone": "01012345678",
  "genderSource": "USER_CONFIRMED",
  "birthDateSource": "USER_CONFIRMED",
  "missingFields": [],
  "profileConfirmedAt": "2026-09-18T12:34:56.123456Z",
  "version": 2
}
```

U05 요청과 응답:

```json
{
  "permissions": [
    {"code":"MICROPHONE","status":"GRANTED"},
    {"code":"LOCATION","status":"DENIED"}
  ]
}
```

```json
{
  "items": [
    {"code":"MICROPHONE","status":"GRANTED","updatedAt":"2026-09-18T12:34:56.123456Z"},
    {"code":"LOCATION","status":"DENIED","updatedAt":"2026-09-18T12:34:56.123456Z"}
  ]
}
```

- 권한 코드: `MICROPHONE`, `LOCATION`
- 상태: `GRANTED`, `DENIED`, `NOT_DETERMINED`
- 배열 크기: 1~2, 같은 권한 코드를 중복 전송할 수 없음

U08 요청:

```json
{"name":"보호자","relationship":"가족","phone":"01087654321"}
```

U09 요청:

```json
{
  "name":"보호자",
  "relationship":"가족",
  "phone":"01087654321",
  "expectedVersion":1
}
```

U10 요청:

```json
{"expectedVersion":1}
```

`ContactView`는 `{id, slot, name, relationship, phone, version}`이며 `slot`은 1 또는 2다. 보호자는 최대 2명이고 본인 또는 다른 보호자와 같은 번호를 등록할 수 없다.

U12 요청과 응답:

```json
{"incomingAlertMode":"SILENT","expectedVersion":1}
```

```json
{"incomingAlertMode":"SILENT","version":2}
```

`incomingAlertMode`는 `RINGTONE`, `SILENT`만 허용한다. 웹 MVP에는 진동 모드가 없다.

### 2.4 안심 메시지 작성 자료

| ID | 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|---|
| M01 | `GET /message-composer?mode={mode}` | `mode`: `SAFETY` 또는 `TEST`, 생략 시 `SAFETY` | `200 MessageComposerView` |

본문, `mode` 이외의 쿼리, 빈 mode, 중복 mode는 허용하지 않는다.

```json
{
  "mode": "SAFETY",
  "recipients": [
    {"id":"uuid","slot":1,"name":"보호자","relationship":"가족","phone":"01087654321","version":1}
  ],
  "identity": {"name":"홍길동","maskedPhone":"010-xxxx-5678"},
  "baseBody": "홍길동(010-xxxx-5678)의 SafeCall 안심 메시지입니다.",
  "templateVersion": 3,
  "isLocationPermissionGranted": true,
  "mapTemplate": {
    "version": 1,
    "urlTemplate": "https://map.naver.com/v5/search/{latitude},{longitude}",
    "coordinateSystem": "WGS84",
    "maxAgeSeconds": 30,
    "maxAccuracyMeters": 100
  },
  "notice": "이 화면에서는 실제 문자가 발송되지 않습니다.",
  "preparedAt": "2026-09-18T12:34:56.123456Z",
  "expiresAt": "2026-09-18T12:39:56.123456Z"
}
```

| 필드 | 형식 | 서버 기준 의미 |
|---|---|---|
| `baseBody` | string | 서버가 사용자 이름과 마스킹 전화번호를 적용해 완성한 기본 메시지 본문. `TEST` 모드에서는 앞에 `[테스트] `가 붙는다. |
| `templateVersion` | integer | 기본 메시지 본문의 서버 템플릿 버전. 현재 값은 `3`이다. |
| `isLocationPermissionGranted` | boolean | 서버에 저장된 위치 권한 상태가 `GRANTED`인지 나타낸다. 현재 브라우저의 실제 권한과 위치 취득 성공까지 보장하지는 않는다. |
| `mapTemplate` | object 또는 null | 검수된 지도 URL 템플릿 설정. 지도 설정 환경변수 6개가 모두 생략된 경우 `null`이다. |
| `mapTemplate.version` | integer | 검수된 지도 템플릿 버전. 양의 정수다. |
| `mapTemplate.urlTemplate` | string | HTTPS 지도 URL 템플릿. `{latitude}`와 `{longitude}`가 각각 정확히 한 번 포함된다. |
| `mapTemplate.coordinateSystem` | string | 서버가 반환하는 좌표계. 현재 `WGS84`로 고정된다. |
| `mapTemplate.maxAgeSeconds` | integer | 사용할 수 있는 위치 정보의 최대 경과 시간. 현재 `30`초다. |
| `mapTemplate.maxAccuracyMeters` | integer | 사용할 수 있는 위치 정확도의 최대 오차. 현재 `100`미터다. |

프론트엔드는 M01 응답의 `baseBody`를 기본 메시지로 표시한다. `isLocationPermissionGranted=true`이고 `mapTemplate`이 null이 아니며, 브라우저에서 얻은 WGS84 위치가 `maxAgeSeconds`와 `maxAccuracyMeters` 기준을 만족할 때만 `urlTemplate`의 `{latitude}`와 `{longitude}`를 실제 좌표로 각각 치환해 위치 링크를 구성한다. 예를 들어 위 예시에서 위도 `37.45`, 경도 `126.70`을 적용하면 `https://map.naver.com/v5/search/37.45,126.70`이 된다.

완성 메시지는 `baseBody`와 생성한 위치 링크를 화면에서 조합한다. M01은 위도·경도, 치환이 끝난 URL 또는 최종 메시지를 요청으로 받거나 저장하지 않으며 SMS도 발송하지 않는다. 지도 설정이 없거나 권한·위치 품질 기준을 충족하지 못하면 `baseBody`만 표시한다.

### 2.5 AI 통화

| ID | 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|---|
| C01 | `POST /calls` | `CreateCall` | `202 CallView`, `Location` 헤더 |
| C02 | `GET /calls/{callId}` | 없음 | `200 CallView` |
| C03 | `GET /calls/{callId}/connection?grantId={uuid}` | `grantId` 선택 | 준비 중 `202 IssuingView`, 완료 `200 ConnectionView` |
| C04 | `POST /calls/{callId}/events` | `CallEvent` | `200 CallView` |
| C05 | `POST /calls/{callId}/heartbeat` | `{}` | `200 HeartbeatView` |
| C06 | `POST /calls/{callId}/end` | `EndCall` | `200 CallView` |

C01 요청:

```json
{
  "clientCallId":"22222222-2222-4222-8222-222222222222",
  "startMode":"STANDARD",
  "scenarioCode":"FOLLOWED",
  "counterpartCode":"FATHER",
  "microphonePermission":"GRANTED"
}
```

- `startMode`: `STANDARD`, `QUICK`; `QUICK`의 상대는 반드시 `FATHER`
- `scenarioCode`: H02가 반환한 4개 코드 중 하나
- `counterpartCode`: `FATHER`, `MOTHER`, `FRIEND`
- `microphonePermission`: 통화 생성 시 `GRANTED`만 허용

`CallView`:

```json
{
  "id":"uuid",
  "clientCallId":"uuid",
  "state":"PREPARING",
  "startMode":"STANDARD",
  "scenarioCode":"FOLLOWED",
  "counterpartCode":"FATHER",
  "displayName":"아빠",
  "createdAt":"2026-09-18T12:34:56.123456Z",
  "ringingAt":null,
  "answeredAt":null,
  "endedAt":null,
  "endReason":null,
  "leaseExpiresAt":"2026-09-18T12:35:26.123456Z",
  "expiresAt":"2026-09-18T12:44:56.123456Z",
  "policyVersion":"mvp-2026-09-11",
  "version":2
}
```

- 상태: `CREATED`, `PREPARING`, `RINGING`, `ACTIVE`, `ENDED`, `FAILED`
- 클라이언트 종료 사유: `USER_ENDED`, `DECLINED`, `BACK_NAVIGATION`, `TAB_HIDDEN`, `PAGE_EXIT`, `PAGE_RELOAD`, `SWITCH_TO_FALLBACK`, `DURATION_LIMIT`
- 서버가 기록할 수 있는 추가 종료 사유: `CONNECTION_FAILED`, `RINGING_FAILED`, `MICROPHONE_FAILED`, `AUDIO_FAILED`, `CONNECTION_LOST`, `SESSION_EXPIRED`, `LOGOUT`, `CONSENT_WITHDRAWN`, `DATA_DELETION`

C03 준비 중 응답:

```json
{"status":"ISSUING","retryAfterMs":250}
```

C03 완료 응답:

```json
{
  "grantId":"uuid",
  "status":"READY",
  "token":"민감한 단기 연결 토큰",
  "model":"models/example",
  "apiVersion":"v1beta",
  "voiceId":"Algieba",
  "responseModalities":["AUDIO"],
  "newSessionExpiresAt":"2026-09-18T12:35:56.123456Z",
  "expiresAt":"2026-09-18T12:44:56.123456Z",
  "uses":1
}
```

- `voiceId`: 세션 초기 setup에 고정하는 Gemini 기본 음성 ID. `FATHER` 통화는 `Algieba`, `FRIEND` 통화는 `Rasalgethi`를 사용하고 `MOTHER`는 검수된 프롬프트 릴리스의 `voiceId`를 사용한다.

C04 요청:

```json
{
  "eventId":"33333333-3333-4333-8333-333333333333",
  "type":"CONNECTED",
  "grantId":"C03 응답의 grantId",
  "occurredAt":"2026-09-18T12:35:00Z",
  "expectedVersion":2,
  "errorCode":null
}
```

- 사건 순서: `CONNECTED` → `RINGING_SHOWN` → `ANSWERED`
- `CONNECTED`에만 `grantId`가 필수다.
- `FAILED`에만 `errorCode`가 필수다.
- 실패 코드: `CONNECTION_FAILED`, `RINGING_FAILED`, `MICROPHONE_FAILED`, `AUDIO_FAILED`, `CONNECTION_LOST`
- 사건마다 새 `eventId`와 최신 `expectedVersion`을 사용하고 같은 사건 재전송에는 기존 값을 유지한다.

C05 응답은 `{state,leaseExpiresAt,expiresAt}`이다. heartbeat는 version이나 전체 통화 상한을 늘리지 않는다.

C06 요청:

```json
{"reason":"USER_ENDED","occurredAt":"2026-09-18T12:40:00Z"}
```

`DURATION_LIMIT`은 C01의 `expiresAt` 20초 전부터만 허용한다. 웹 데모는 `expiresAt` 15초 전에 AI 마무리 발화를 요청하고 재생을 끝낸 다음 이 사유로 C06을 호출한다. 이 범위보다 이르게 보내면 `400 INVALID_REQUEST`이다.

### 2.6 운영 사건

| ID | 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|---|
| O01 | `POST /telemetry/events` | `EventBatch` | `202 EventCounts` |

```json
{
  "events":[{
    "eventId":"44444444-4444-4444-8444-444444444444",
    "callId":null,
    "category":"MESSAGE_COMPOSER",
    "code":"COMPOSER_OPENED",
    "isSuccess":true,
    "latencyMs":320,
    "networkType":"UNKNOWN",
    "occurredAt":"2026-09-18T12:34:56Z"
  }]
}
```

- `events`: 1~20개
- `latencyMs`: 0~3,600,000
- `networkType`: `WIFI`, `CELLULAR`, `OFFLINE`, `UNKNOWN`
- `callId`가 있으면 현재 웹 세션 소유 통화여야 한다.
- 같은 `eventId`와 같은 본문은 중복으로 계산하고, 다른 본문은 충돌이다.
- 세션별 UTC 분당 신규 120건 제한을 적용한다.

성공 응답:

```json
{"acceptedCount":1,"duplicateCount":0}
```

허용 category/code 조합:

| category | code |
|---|---|
| `PERMISSION` | `MICROPHONE_PERMISSION_REVIEWED`, `LOCATION_PERMISSION_REVIEWED`, `PERMISSION_QUERY_UNAVAILABLE` |
| `CALL` | `LIVE_CONNECT_STARTED`, `LIVE_CONNECT_SUCCEEDED`, `LIVE_CONNECT_FAILED`, `RINGING_SHOWN`, `RINGING_FAILED`, `PAGE_EXITED`, `PAGE_RELOADED` |
| `AUDIO` | `FIRST_AUDIO_PLAYED`, `AUDIO_INTERRUPTED` |
| `GESTURE` | `QUICK_START_SELECTED`, `QUICK_START_CANCELLED` |
| `LOCATION` | `LOCATION_AVAILABLE`, `LOCATION_UNAVAILABLE` |
| `MESSAGE_COMPOSER` | `COMPOSER_OPENED`, `COMPOSER_OPEN_FAILED` |
| `SOS` | `SOS_GUIDE_VIEWED`, `SOS_GUIDE_FAILED` |
| `FALLBACK` | `FALLBACK_STARTED`, `FALLBACK_ENDED` |

### 2.7 이용 기록·데이터 삭제

| ID | 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|---|
| R01 | `GET /me/usage-history?limit={1..100}&cursor={cursor}` | 기본 limit 20 | `200 UsageHistoryView` |
| R02 | `POST /me/data-deletions` | `DeletionRequest` | `202 DeletionView`, 접수증 쿠키 발급 |
| R03 | `GET /data-deletions/{jobId}` | 회원 세션 또는 접수증 쿠키 | `200 DeletionView` |

R01 응답:

```json
{
  "items":[{
    "id":"uuid",
    "state":"ENDED",
    "startMode":"STANDARD",
    "scenarioCode":"FOLLOWED",
    "counterpartCode":"FATHER",
    "displayName":"아빠",
    "createdAt":"2026-09-18T12:34:56.123456Z",
    "answeredAt":"2026-09-18T12:35:10.123456Z",
    "endedAt":"2026-09-18T12:40:00.123456Z",
    "endReason":"USER_ENDED"
  }],
  "nextCursor":null
}
```

R02 요청:

```json
{"scope":"USAGE_HISTORY","isConfirmed":true}
```

- `scope`: `ACCOUNT`, `USAGE_HISTORY`
- `isConfirmed`: 반드시 `true`
- 접수증은 `__Host-safecall-deletion` Secure·HttpOnly 쿠키로만 반환한다.
- `ACCOUNT` 접수가 성공하면 해당 회원의 활성 웹 세션을 즉시 폐기하고, 백그라운드 정리 작업이 다음 실행 주기에 로컬 계정과 종속 데이터를 삭제한다. 이후 A05는 새 익명 세션을 발급하며, 삭제 상태는 회원 세션이 아니라 접수증 쿠키로 R03을 조회한다.

`DeletionView`:

```json
{
  "id":"uuid",
  "scope":"USAGE_HISTORY",
  "status":"PENDING",
  "requestedAt":"2026-09-18T12:34:56.123456Z",
  "dueAt":"2026-09-19T12:34:56.123456Z",
  "completedAt":null,
  "errorCode":null
}
```

상태는 `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`다. R03은 작업 존재 여부와 소유권을 함께 숨기므로 잘못된 ID·타인 작업·잘못되거나 만료된 접수증을 모두 `404 RESOURCE_NOT_FOUND`로 반환한다.

## 3. 엔드포인트별 오류

아래 표는 공통 형식·보안 오류를 제외하고 각 호출 경로에서 직접 발생 가능한 업무 오류를 정리한다. 모든 변경 API에는 `ORIGIN_NOT_ALLOWED`, `CSRF_INVALID`, `SESSION_EXPIRED`, `INVALID_REQUEST`가 추가로 적용될 수 있다. 모든 API에는 `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `RESOURCE_NOT_FOUND`, `REQUEST_IN_PROGRESS`, `INTERNAL_SERVER_ERROR` 등 공통 처리기가 적용된다.

| API | 상태·코드 |
|---|---|
| A05 | `400 INVALID_REQUEST`, `403 ORIGIN_NOT_ALLOWED` |
| A01 | `401 SESSION_EXPIRED`, `409 ALREADY_AUTHENTICATED`, `409 ACCOUNT_DELETION_PENDING`, `429 RATE_LIMITED` |
| A02 | `401 SESSION_EXPIRED`, `409 ALREADY_AUTHENTICATED`, `409 ACCOUNT_DELETION_PENDING`, `429 RATE_LIMITED` |
| A04 | `401 AUTHENTICATION_REQUIRED`, `401 SESSION_EXPIRED`, `503 LOGOUT_FAILED` |
| H01 | `401 AUTHENTICATION_REQUIRED`, `401 SESSION_EXPIRED`, `409 ACCOUNT_DELETION_PENDING` |
| H02 | H01 오류 + `503 PROMPT_NOT_READY` |
| U01/U04/U07/U11 | `401 SESSION_EXPIRED`, `403 LOGIN_REQUIRED`, `409 ACCOUNT_DELETION_PENDING` |
| U02 | 조회 오류 + `400 VALIDATION_FAILED`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS`, `409 CONTACT_PHONE_CONFLICT`, `409 VERSION_CONFLICT`, `422 PROFILE_REQUIRED`, `422 INVALID_PHONE`, `422 INVALID_BIRTH_DATE` |
| U05 | 조회 오류 + `400 INVALID_REQUEST`, `400 VALIDATION_FAILED`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS` |
| U08 | 조회 오류 + `400 VALIDATION_FAILED`, `409 CONTACT_LIMIT_REACHED`, `409 CONTACT_PHONE_CONFLICT`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS`, `422 INVALID_PHONE` |
| U09 | U08 오류 + `404 CONTACT_NOT_FOUND`, `409 VERSION_CONFLICT` |
| U10 | 조회 오류 + `404 CONTACT_NOT_FOUND`, `409 VERSION_CONFLICT`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS` |
| U12 | 조회 오류 + `400 VALIDATION_FAILED`, `409 VERSION_CONFLICT`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS`, `422 INVALID_ALERT_MODE`, `503 SETTINGS_SAVE_FAILED` |
| M01 | `400 INVALID_REQUEST`, `401 SESSION_EXPIRED`, `403 LOGIN_REQUIRED`, `409 PROFILE_REQUIRED`, `409 CONTACT_REQUIRED`, `409 CALL_ALREADY_OPEN`, `409 DATA_CLEANUP_PENDING` |
| C01 | `400 VALIDATION_FAILED`, `401 AUTHENTICATION_REQUIRED`, `401 SESSION_EXPIRED`, `403 MICROPHONE_REQUIRED`, `409 ACCOUNT_DELETION_PENDING`, `409 CALL_ALREADY_OPEN`, `409 DATA_CLEANUP_PENDING`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS`, `422 PROFILE_REQUIRED`, `422 INVALID_CALL_OPTION`, `422 INVALID_QUICK_START`, `429 RATE_LIMITED`, `429 CALL_CAPACITY_REACHED`, `503 PROMPT_NOT_READY`, `503 GEMINI_VALIDATION_REQUIRED` |
| C02 | `401 AUTHENTICATION_REQUIRED`, `401 SESSION_EXPIRED`, `404 RESOURCE_NOT_FOUND`, `409 ACCOUNT_DELETION_PENDING` |
| C03 | C02 오류 + `409 CALL_TERMINAL`, `409 CONNECTION_ALREADY_USED`, `409 DATA_CLEANUP_PENDING`, `410 CONNECTION_GRANT_EXPIRED`, `422 PROFILE_REQUIRED`, `503 CONNECTION_ISSUE_UNKNOWN`, `503 PROMPT_NOT_READY` |
| C04 | C02 오류 + `400 INVALID_REQUEST`, `400 VALIDATION_FAILED`, `409 CALL_TERMINAL`, `409 CALL_TRANSITION_INVALID`, `409 DATA_CLEANUP_PENDING`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS`, `409 STALE_CONNECTION_GENERATION`, `409 VERSION_CONFLICT`, `422 PROFILE_REQUIRED` |
| C05 | C02 오류 + `409 CALL_TERMINAL`, `409 DATA_CLEANUP_PENDING`, `422 PROFILE_REQUIRED` |
| C06 | C02 오류 + `400 INVALID_REQUEST`, `400 VALIDATION_FAILED`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS` |
| O01 | `400 INVALID_REQUEST`, `400 VALIDATION_FAILED`, `401 AUTHENTICATION_REQUIRED`, `401 SESSION_EXPIRED`, `404 RESOURCE_NOT_FOUND`, `409 ACCOUNT_DELETION_PENDING`, `409 IDEMPOTENCY_CONFLICT`, `422 INVALID_EVENT`, `429 RATE_LIMITED` |
| R01 | `400 INVALID_REQUEST`, `400 INVALID_CURSOR`, `401 SESSION_EXPIRED`, `403 LOGIN_REQUIRED`, `409 ACCOUNT_DELETION_PENDING` |
| R02 | `400 VALIDATION_FAILED`, `401 SESSION_EXPIRED`, `403 LOGIN_REQUIRED`, `409 ACCOUNT_DELETION_PENDING`, `409 CALL_ALREADY_OPEN`, `409 DELETION_RECEIPT_EXPIRED`, `409 IDEMPOTENCY_CONFLICT`, `409 REQUEST_IN_PROGRESS` |
| R03 | `400 INVALID_REQUEST`, `404 RESOURCE_NOT_FOUND` |

## 4. 전체 오류 코드와 메시지

다음 표는 현재 `ErrorCode`에 선언된 모든 오류를 포함한다. “예약”은 현재 공개 컨트롤러 호출 경로에서 직접 발생시키지 않지만 서버 enum에는 남아 있는 코드다.

| HTTP | 응답 `code` | 응답 `message` | 대표 조건 |
|---:|---|---|---|
| 400 | `INVALID_REQUEST` | 요청 형식이 올바르지 않습니다. | JSON·헤더·쿼리·UUID·시각 형식 오류 |
| 400 | `INVALID_CURSOR` | 이용 기록 조회 위치가 올바르지 않습니다. | R01 커서 형식·서명·소유권 오류 |
| 400 | `VALIDATION_FAILED` | 입력값이 올바르지 않습니다. | Bean Validation 또는 문자열 업무 검증 실패 |
| 401 | `AUTHENTICATION_REQUIRED` | 인증이 필요합니다. | 익명 세션으로 인증 기능 접근 |
| 401 | `SESSION_EXPIRED` | 세션이 만료되었습니다. 다시 로그인해 주세요. | 세션 없음·만료·폐기 |
| 401 | `TOKEN_REUSED` | 이미 사용된 인증 정보가 감지되어 세션이 종료되었습니다. | 예약 |
| 403 | `ORIGIN_NOT_ALLOWED` | 허용되지 않은 요청 출처입니다. | Origin·초기화 출처 불일치 |
| 403 | `CSRF_INVALID` | 요청 출처 또는 CSRF 토큰을 확인해 주세요. | CSRF 누락·불일치·중복 |
| 403 | `LOGIN_REQUIRED` | 가상 로그인이 필요합니다. | 게스트가 회원 전용 API 접근 |
| 403 | `ACCESS_DENIED` | 접근할 수 없습니다. | 기본 오류 라우팅의 403 |
| 403 | `MICROPHONE_REQUIRED` | 마이크 권한이 필요합니다. | C01 권한이 GRANTED가 아님 |
| 404 | `RESOURCE_NOT_FOUND` | 대상을 찾을 수 없습니다. | 경로·통화·작업·소유권 불일치 |
| 404 | `CONTACT_NOT_FOUND` | 보호자 정보를 찾을 수 없습니다. | U09/U10 연락처 없음 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 요청 방식입니다. | 잘못된 HTTP 메서드 |
| 406 | `NOT_ACCEPTABLE` | JSON 응답을 요청해 주세요. | 허용되지 않는 Accept |
| 409 | `ALREADY_AUTHENTICATED` | 이미 로그인되어 있습니다. | 회원 상태에서 A01/A02 호출 |
| 409 | `PROFILE_REQUIRED` | 이름과 전화번호를 확인해 주세요. | 내부 `MESSAGE_PROFILE_REQUIRED`가 M01에서 같은 공개 코드로 변환됨 |
| 409 | `CONTACT_REQUIRED` | 비상 연락망을 등록해 주세요. | M01 보호자 없음 또는 2명 초과 |
| 409 | `CONTACT_PHONE_CONFLICT` | 본인 또는 다른 보호자와 같은 번호입니다. | 프로필·연락처 번호 충돌 |
| 409 | `STALE_CONNECTION_GENERATION` | 이전 연결 세대입니다. | C04의 grantId가 현재 grant가 아님 |
| 409 | `CALL_ALREADY_OPEN` | 이미 진행 중인 통화가 있습니다. | 열린 통화와 충돌 |
| 409 | `CALL_TERMINAL` | 이미 종료된 통화입니다. | 종료 통화에 활성 작업 요청 |
| 409 | `CALL_TRANSITION_INVALID` | 현재 통화 상태에서 처리할 수 없는 사건입니다. | C04 사건 순서 위반 |
| 409 | `CONNECTION_ALREADY_USED` | 이미 사용된 연결 정보입니다. | C03에서 사용 완료 grant 조회 |
| 409 | `VERSION_CONFLICT` | 정보가 변경되었습니다. 다시 조회해 주세요. | 오래된 expectedVersion |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 요청 키에 다른 내용이 전달되었습니다. | 같은 멱등 키/eventId의 본문 불일치 |
| 409 | `REQUEST_IN_PROGRESS` | 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요. | 같은 작업 처리 중 또는 DB 잠금 경쟁 |
| 409 | `AUTH_REPLAY_EXPIRED` | 인증 결과의 재조회 시간이 만료되었습니다. | 예약 |
| 409 | `REFRESH_REPLAY_EXPIRED` | 인증 갱신 결과의 재조회 시간이 만료되었습니다. | 예약 |
| 409 | `ACCOUNT_DELETION_PENDING` | 계정 삭제를 처리 중입니다. | 계정 삭제 대기 회원 접근 |
| 409 | `CONTACT_LIMIT_REACHED` | 보호자는 최대 2명까지 등록할 수 있습니다. | U08 세 번째 보호자 등록 |
| 409 | `CONTACT_PHONE_DUPLICATE` | 본인 또는 다른 보호자와 같은 번호는 등록할 수 없습니다. | 예약; 현재 구현은 CONTACT_PHONE_CONFLICT 사용 |
| 409 | `DATA_CLEANUP_PENDING` | 관련 데이터 정리를 처리 중입니다. | 통화·메시지 관련 정리 중 |
| 409 | `DELETION_RECEIPT_EXPIRED` | 삭제 접수증 재조회 시간이 만료되었습니다. | R02 멱등 응답 재생 만료 |
| 410 | `CONNECTION_GRANT_EXPIRED` | 새 연결을 시작할 수 있는 시간이 지났습니다. | C03 새 세션 시작 기한 만료 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | JSON 형식으로 요청해 주세요. | Content-Type 오류 |
| 422 | `PROFILE_REQUIRED` | 이름과 전화번호를 확인해 주세요. | U02/C01/C03/C04/C05 프로필 부족 |
| 422 | `INVALID_ALERT_MODE` | 지원하지 않는 알림 모드입니다. | RINGTONE/SILENT 외 값 |
| 422 | `VALIDATION_FAILED` | 입력값을 확인해 주세요. | 내부 BUSINESS_VALIDATION_FAILED의 공개 코드; 현재 직접 사용 없음 |
| 422 | `INVALID_CALL_OPTION` | 통화 선택지를 확인해 주세요. | 상황·상대 코드 오류 |
| 422 | `INVALID_QUICK_START` | 빠른 시작 상대는 아빠여야 합니다. | QUICK + FATHER 외 상대 |
| 422 | `INVALID_RECONNECT_SOURCE` | 새 연결을 시작할 수 없는 이전 통화입니다. | 예약 |
| 422 | `INVALID_EVENT` | 허용된 운영 사건과 성공 여부를 확인해 주세요. | O01 category/code/success/network 조합 오류 |
| 422 | `INVALID_PHONE` | 올바른 국내 휴대폰 번호를 입력해 주세요. | 전화번호 검증 실패 |
| 422 | `INVALID_BIRTH_DATE` | 올바른 생년월일을 입력해 주세요. | 생년월일 형식·범위 오류 |
| 422 | `PROFILE_INCOMPLETE` | 이름과 전화번호를 확인해 주세요. | 예약 |
| 429 | `RATE_LIMITED` | 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요. | 인증·통화·운영 사건 한도 초과 |
| 429 | `CALL_CAPACITY_REACHED` | 현재 체험 인원이 많습니다. 잠시 후 다시 시도해 주세요. | 서비스 전체 활성 통화 한도 초과 |
| 500 | `INTERNAL_SERVER_ERROR` | 요청을 처리하지 못했습니다. | 처리되지 않은 서버 오류 |
| 503 | `GEMINI_VALIDATION_REQUIRED` | Gemini 연결 설정 검수가 필요합니다. | C01 모델 검수값 불일치 |
| 503 | `PROMPT_NOT_READY` | 통화 준비가 완료되지 않았습니다. | 선택지·프롬프트·Gemini 설정 미완료 |
| 503 | `CONNECTION_ISSUE_UNKNOWN` | 연결 정보 발급 결과를 확인할 수 없습니다. 새 통화를 시작해 주세요. | C03 발급 결과 불명 |
| 503 | `SETTINGS_SAVE_FAILED` | 설정을 저장하지 못했습니다. 다시 시도해 주세요. | U12 DB 저장 실패 |
| 503 | `LOGOUT_FAILED` | 로그아웃을 완료하지 못했습니다. 다시 시도해 주세요. | A04 DB 처리 실패 |

## 5. 프론트 처리 원칙

- `400`, `415`, `422`: 요청을 고친 뒤 새 논리 작업으로 다시 보낸다.
- `401`: A05로 세션을 다시 초기화하고 사용자의 로그인 상태를 재판단한다.
- `403 CSRF_INVALID`: A05로 최신 CSRF를 받은 뒤 사용자 작업이 여전히 유효한 경우 재시도한다.
- `404`: 다른 사용자·세션·통화 페이지의 존재 여부를 추측하지 않고 현재 화면을 정리한다.
- `409 VERSION_CONFLICT`: 최신 리소스를 다시 조회하고 사용자의 수정을 재적용할지 확인한다.
- `409 IDEMPOTENCY_CONFLICT`: 기존 키를 다른 작업에 재사용하지 않는다.
- `409 REQUEST_IN_PROGRESS`, `429 RATE_LIMITED`, `429 CALL_CAPACITY_REACHED`: `Retry-After` 이후 같은 논리 작업이면 같은 멱등 키와 본문으로 재시도한다.
- `410`: 이전 연결 토큰을 다시 사용하지 않고 새 통화를 시작한다.
- `500`, `503`: 응답의 고정 메시지만 표시하고 내부 예외·SQL·외부 공급자 원문을 노출하지 않는다.

## 6. 구현 근거

- 엔드포인트·성공 상태: 각 `*Controller.java`
- 요청·응답 필드와 enum: 각 `*Dtos.java`
- 오류 상태·코드·메시지: `common/error/ErrorCode.java`
- 오류 JSON: `common/error/ErrorResponse.java`, `ErrorWriter.java`, `GlobalExceptionHandler.java`, `ApiErrorController.java`
- Origin·CSRF·멱등 키 헤더 검사: `common/config/RequestFilter.java`
- 엔드포인트별 업무 오류: 각 서비스 클래스의 실제 분기

# 사용자·홈

[테스트 시작](README.md) · [회원 로그인](auth.md) 후 실행

## 프로필 U01/U02

1. U01 `GET /me/profile`에서 현재 값과 version을 읽는다.
2. U02 `PATCH /me/profile`에 아래 **6개 필드 전부**와 현재 CSRF·새 Idempotency-Key를 보낸다. 예시 값은 테스트용이며 version은 U01 값으로 바꾼다.
3. 200 응답과 U01 재조회에서 확인 값·version을 확인한다. 화면 전환은 프론트 캐시로 처리한다.

```json
{"name":"홍길동","gender":null,"birthDate":null,"phone":"01012345678","isConfirmed":true,"expectedVersion":1}
```

gender/birthDate는 명시적 null이 가능하다. 새 성별·생일 저장에는 현재 AI 동의가 필요하며, AI 동의가 없으면 조회 시 마스킹한다. 전화번호는 한국 번호로 정규화한다. 예를 들어 `+82 10-1234-5678`은 `01012345678`이다.

## 보호자 U07~U10

| 순서 | 호출 | 본문·기대 결과 |
|---|---|---|
| 조회 | U07 GET `/me/emergency-contacts` | items의 id·slot·version 확인 |
| 등록 | U08 POST `/me/emergency-contacts` | `{"name":"테스트 보호자","relationship":"가족","phone":"01087654321"}`, 201 |
| 수정 | U09 PATCH `/me/emergency-contacts/{id}` | name·relationship·phone·expectedVersion 전부 전송, 200 |
| 삭제 | U10 DELETE `/me/emergency-contacts/{id}` | `{"expectedVersion":1}`, 204 |

변경 요청마다 현재 CSRF·새 Idempotency-Key를 사용한다. 보호자는 최대 2명이며 본인 번호·다른 보호자 번호와 중복될 수 없다. M01 테스트를 위해 최소 1명은 남겨 둔다. 다른 회원 ID는 404, 오래된 version은 409다.

## 설정 U11/U12

U11 `GET /me/settings`에서 version을 읽은 뒤 U12 `PATCH /me/settings`에 `{"incomingAlertMode":"SILENT","expectedVersion":1}`을 보내 200을 확인한다. RINGTONE/SILENT만 허용하며 다른 값은 422 INVALID_ALERT_MODE다.

## 동의 U04~U05

| 호출 | 확인 |
|---|---|
| U04 GET `/me/consents` | decisionVersion/currentVersion, action, isEffective. currentVersion은 항상 1 |
| U05 POST `/me/consents` | 아래 decisions 1~3개, 중복 코드 금지. version은 항상 1 |

```json
{"decisions":[{"code":"LOCATION_PROCESSING","version":1,"action":"GRANTED"}]}
```

U05는 GRANTED/DECLINED만 받는다. 기존 GRANTED를 DECLINED로 바꾸는 요청은 WITHDRAWAL_REQUIRED다. **철회 U06은 다른 기능 검증 후 [철회·삭제 흐름](history-deletion.md#동의-철회-u06)**에서 실행한다.

## 홈 H01/H02

프로필·동의·연락처 저장 후 H01 `GET /home`, H02 `GET /call-options`를 조회한다.

| 시나리오 | 기대 결과 |
|---|---|
| 게스트 H01 | 메시지 LOGIN_REQUIRED, guardianCount=0, settingsMode=LOGIN_ONLY |
| 회원 H01 | 프로필·동의·보호자·열린 통화·정리 상태에 맞는 messageBlockReasons |
| AI 동의만 없는 회원 | 메시지 자격을 AI 동의 자체로 제한하지 않음. 진행 중 AI 정리는 별도 차단 |
| H02 | 상황 4개, 상대 3개, QUICK의 FATHER/1000ms, catalogVersion=3 |
| 인증 후 H02의 ETag 재조회 | 같은 태그면 304. 익명은 태그만으로 조회 불가 |

H01은 작성 자격만 반환한다. 실제 자료는 [M01](messages.md)을 호출한다. 이름·번호·날짜 검증, 타인 리소스 접근, 오래된 version, 같은 멱등 키의 다른 본문도 확인한다.

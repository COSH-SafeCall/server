# 인증·온보딩

[테스트 시작](README.md) · 회원 로그인은 [카카오 설정](../setup.md#카카오-앱-등록) 필요

## 게스트: A05 → A01 → A05 → A06/A07

| 순서 | 호출 | 입력·확인 |
|---|---|---|
| 1 | A05 GET `/auth/session` | 익명 세션과 CSRF 확인. 반복 조회에서 같은 세션의 CSRF 유지 |
| 2 | A01 POST `/auth/guest` | `{}`, 현재 CSRF. 200 후 A05를 다시 조회하여 새 CSRF 사용 |
| 3 | A06 GET `/onboarding` | 현재 step과 version 확인 |
| 4 | A07 POST `/onboarding/advance` | PERMISSIONS, SOS_GUIDE 순서로 아래 본문 전송. 매 단계 응답 version 사용 |
| 5 | A06 GET `/onboarding` | COMPLETE 확인 |

```json
{"step":"PERMISSIONS","expectedVersion":1,"isNoticeReviewed":true}
```

SOS_GUIDE에서는 step을 바꾸고 최신 version을 사용한다. 각 새 단계는 새 Idempotency-Key다. 안내 확인이 실제 마이크·위치 권한 허용을 증명하지는 않는다. 게스트 재진입은 만료를 연장하지 않는다.

## 회원: 동의 → OAuth → 프로필 → 온보딩

1. U03 `GET /documents/{code}`로 PRIVACY_PROCESSING, AI_CALL, LOCATION_PROCESSING을 각각 읽는다. 로그인 없이 조회하며 발행된 version을 기록한다.
2. A02 `POST /auth/kakao/authorization`에 아래 본문과 현재 CSRF를 보낸다. 예시 version=1은 실제 값으로 바꾼다. 필수 두 문서는 GRANTED, 위치는 선택이다.
3. 200 응답의 authorizationUrl로 **브라우저를 이동**한다. callback을 Swagger에서 직접 조립하거나 반복 호출하지 않는다.
4. callback의 303 이동이 끝나면 같은 origin Swagger로 돌아와 A05를 다시 실행한다. 백엔드만 실행 중이면 최종 SPA 화면이 없어도 callback 처리와 A05 결과를 구분해 확인한다.
5. A06 step/version을 읽고 다음 표대로 진행한다. 각 A07은 200이며 현재 step/version과 해당 단계의 추가 필드만 보낸다.

```json
{
  "purpose":"LOGIN",
  "decisions":[
    {"code":"PRIVACY_PROCESSING","version":1,"action":"GRANTED"},
    {"code":"AI_CALL","version":1,"action":"GRANTED"},
    {"code":"LOCATION_PROCESSING","version":1,"action":"DECLINED"}
  ]
}
```

| A06의 현재 step | 먼저 수행 | A07 본문에 추가할 값 | 다음 단계 |
|---|---|---|---|
| PROFILE | [U01/U02 프로필 확인](users.md#프로필-u01u02) | 없음 | 신규 CONTACTS / 기존 ACTIVE 회원 PERMISSIONS |
| CONTACTS | [U07/U08 보호자 등록](users.md#보호자-u07u10) | 없음 | PERMISSIONS |
| PERMISSIONS | 권한 안내 확인 | `isNoticeReviewed: true` | SOS_GUIDE |
| SOS_GUIDE | SOS 안내 확인 | `isNoticeReviewed: true` | MESSAGE_TEST |
| MESSAGE_TEST | [M01 TEST 확인](messages.md) 또는 건너뛰기 | `testDecision: COMPLETED` 또는 `SKIP` | COMPLETE |
| COMPLETE | H01 홈 확인 | A07을 더 보내지 않음 | 완료 |

PROFILE의 A07 예시는 `{"step":"PROFILE","expectedVersion":1}`이다. expectedVersion은 U01 프로필 version이 아니라 **A06 온보딩 version**이다. 실제 응답 step을 따르며 예약 값 CONSENTS를 강제로 보내지 않는다. 보호자를 등록하지 않아도 진행할 수 있지만 M01은 CONTACT_REQUIRED다.

## 재인증과 로그아웃

민감 작업 직전에 A02에 `{"purpose":"REAUTH"}`만 보낸다. decisions는 넣지 않는다. 반환 URL로 이동하여 **같은 카카오 계정**으로 인증하고 A05를 갱신한다. 성공 후 5분 내 ACCOUNT 삭제 또는 PRIVACY_PROCESSING 철회를 접수한다. 일반 LOGIN으로 대체할 수 없다.

모든 기능 테스트를 마친 뒤 A04 `POST /auth/logout`에 `{}`와 CSRF를 보내 204를 확인한다. 현재 세션과 열린 통화가 정리된다. 다시 시작하려면 A05부터 진행한다.

## 실패 확인

| 시나리오 | 기대 결과 |
|---|---|
| 필수 동의 거절·임의 문서 버전 | OAuth 시작 거절 |
| 유효 state로 로그인 취소 | 303 `/login?reason=cancelled` |
| 만료·재사용·다른 시작 쿠키의 state | 303 `/login?reason=oauth_failed` |
| state 누락 또는 state/code/error 중복 | 400 |
| REAUTH에서 다른 계정 | 403 REAUTH_ACCOUNT_MISMATCH, 계정 교체 없음 |
| 알려진 카카오 인앱 REAUTH | 시작 403 / callback 303 external_browser_required |
| LOGIN만 하고 민감 작업 수행 | 403 REAUTHENTICATION_REQUIRED |

303은 개발자 도구 Network의 기존 이동 요청에서 확인한다. 공유 기록에는 code/state 쿼리·쿠키를 남기지 않는다.

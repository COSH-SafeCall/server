# 인증·로그인 후 온보딩

[테스트 시작](README.md) · [프론트 연동 계약](../frontend-onboarding.md) · [카카오 설정](../setup.md#카카오-앱-등록)

## 게스트: A05 → A01 → A05

A05 GET `/auth/session`으로 익명 세션과 CSRF를 준비한 뒤 A01 POST `/auth/guest`에 `{}`와 CSRF를 보낸다. 다시 A05를 호출해 새 CSRF를 사용한다. 권한·SOS 안내는 프론트에서 처리한다. 게스트 재진입은 만료를 연장하지 않는다.

## 회원: OAuth → 동의 → 프로필·연락처 저장

1. A05로 세션·CSRF를 준비한다.
2. A02 POST `/auth/kakao/authorization`에 `{"purpose":"LOGIN"}`만 보낸다. decisions를 보내면 400이다.
3. 응답 authorizationUrl로 브라우저를 이동한다. callback을 직접 조립하거나 반복 호출하지 않는다.
4. callback의 303 이동이 끝나면 같은 origin Swagger에서 A05를 다시 실행한다. 신규 회원의 카카오 프로필은 자동 저장하지 않는다.
5. U01로 빈 초기 프로필과 version을 조회한다. 기존 개인정보 동의가 유효하면 저장된 프로필을 반환한다.
6. 개인정보 화면의 다음 클릭 한 번에서 아래 U05 요청 → U02 프로필 저장 → U08/U09/U10 연락처 변경 순으로 실행한다. Swagger에서는 각각 순서대로 실행한다.

```json
{"decisions":[
  {"code":"PRIVACY_PROCESSING","version":1,"action":"GRANTED"},
  {"code":"AI_CALL","version":1,"action":"GRANTED"},
  {"code":"LOCATION_PROCESSING","version":1,"action":"DECLINED"}
]}
```

U05·U02·연락처 변경에는 요청마다 Idempotency-Key를 사용한다. 부분 실패하면 성공한 저장을 반복 생성하지 않고 실패 요청만 재시도한다. 동의 저장 전에 프로필·연락처를 보내면 CONSENT_REQUIRED다.

A06·A07은 제거됐다. 서버는 화면 단계·안내 확인·온보딩 완료를 저장하지 않는다. 첫 화면 진입과 화면 캐시 만료는 프론트 책임이다. 보호자가 없어도 통화는 가능하지만 M01은 CONTACT_REQUIRED다.

## 재인증과 로그아웃

민감 작업 직전에 A02에 `{"purpose":"REAUTH"}`만 보낸다. decisions는 넣지 않는다. 반환 URL로 이동하여 **같은 카카오 계정**으로 인증하고 A05를 갱신한다. 성공 후 5분 내 ACCOUNT 삭제 또는 PRIVACY_PROCESSING 철회를 접수한다. 일반 LOGIN으로 대체할 수 없다.

모든 기능 테스트를 마친 뒤 A04 `POST /auth/logout`에 `{}`와 CSRF를 보내 204를 확인한다. 현재 세션과 열린 통화가 정리된다. 다시 시작하려면 A05부터 진행한다.

## 실패 확인

| 시나리오 | 기대 결과 |
|---|---|
| 동의 없이 LOGIN 시작 | 200 |
| A02에 decisions 포함 | 400 INVALID_REQUEST |
| 유효 state로 로그인 취소 | 303 `/login?reason=cancelled` |
| 만료·재사용·다른 시작 쿠키의 state | 303 `/login?reason=oauth_failed` |
| state 누락 또는 state/code/error 중복 | 400 |
| REAUTH에서 다른 계정 | 403 REAUTH_ACCOUNT_MISMATCH, 계정 교체 없음 |
| 알려진 카카오 인앱 REAUTH | 시작 403 / callback 303 external_browser_required |
| LOGIN만 하고 민감 작업 수행 | 403 REAUTHENTICATION_REQUIRED |

303은 개발자 도구 Network의 기존 이동 요청에서 확인한다. 공유 기록에는 code/state 쿼리·쿠키를 남기지 않는다.

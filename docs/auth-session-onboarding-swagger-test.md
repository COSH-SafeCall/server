# 인증·온보딩 검증 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [공통 요청 함수](swagger-test-guide.md), 회원 흐름은 [카카오 설정](kakao-token-test-setup.md) 추가

경로의 `/api/v1` 접두사는 생략한다. A05는 익명 상태에서도 초기화할 수 있다. 회원 테스트에 앞서 공개 U03의 동의 문서 3종이 발행되어 있어야 한다.

게스트 세션·온보딩 확인에는 카카오 로그인이 필요하지 않다. 게스트로 실제 AI 통화까지 확인하려면 Gemini와 프롬프트 설정을 별도로 준비한다.

## 게스트

1. A05를 두 번 조회한다. 같은 익명 세션의 CSRF가 유지되어야 한다.
2. A01 `POST /auth/guest`에 `{}`를 전달한다. 쿠키와 CSRF가 교체되므로 `refreshSession()`을 다시 실행한다. 반복 진입은 게스트 만료를 연장하지 않는다.
3. A06 `/onboarding`에서 step/version을 읽는다. A07에 `{"step":"PERMISSIONS","expectedVersion":현재버전,"isNoticeReviewed":true}`를 보낸다.
4. SOS_GUIDE를 같은 방식으로 진행한다. 안내 확인은 브라우저 권한 허용을 증명하지 않는다. 다음 상태는 COMPLETE다.

## 카카오 회원

1. U03으로 동의 문서 PRIVACY_PROCESSING, AI_CALL, LOCATION_PROCESSING의 발행 버전을 각각 읽는다.
2. A02 `POST /auth/kakao/authorization`에 `purpose: LOGIN`과 세 문서의 `{code, version, action}` 결정을 전달한다. 필수 두 문서는 GRANTED이고 위치는 선택 가능하다.
3. 반환된 authorizationUrl로 브라우저를 이동한다. 서버 callback은 state와 같은 브라우저 세션을 대조하고 code를 한 번 교환한 뒤 303으로 이동한다.
4. A05로 새 CSRF를 받는다. [U02 프로필 확인](user-profile-consent-contacts-settings-swagger-test.md)을 마친 뒤 아래 표에 따라 A07을 진행한다. 신규 회원은 PROFILE → CONTACTS → PERMISSIONS → SOS_GUIDE → MESSAGE_TEST → COMPLETE 순서다. 기존 ACTIVE 회원의 새 로그인은 PROFILE 이후 CONTACTS를 건너뛰므로 응답의 step을 따른다.
5. COMPLETE는 완료 상태이며 진행 요청을 더 보내지 않는다. CONSENTS는 예약 단계이며 재동의 화면으로 전환하지 않는다.

| 현재 step | A07 추가 필드 | 성공 후 확인 |
|---|---|---|
| PROFILE | 없음 | U02로 확인한 이름·전화번호 필요; CONTACTS 또는 PERMISSIONS |
| CONTACTS | 없음 | PERMISSIONS. 연락망이 없으면 이후 메시지 작성 자격은 제한됨 |
| PERMISSIONS | `isNoticeReviewed: true` | SOS_GUIDE. 실제 권한 허용의 증거는 아님 |
| SOS_GUIDE | `isNoticeReviewed: true` | 회원 MESSAGE_TEST / 게스트 COMPLETE |
| MESSAGE_TEST | `testDecision: SKIP` 또는 `COMPLETED` | COMPLETE. 현재 실제 작성 M01은 후속 범위이므로 서버 흐름 검증에는 SKIP 사용 |

모든 A07 본문에는 현재 `step`, `expectedVersion`이 필요하며 다른 단계의 추가 필드는 넣지 않는다. 같은 요청 재시도는 Idempotency-Key와 본문을 유지한다.

## 민감 작업과 실패

A02에 `{"purpose":"REAUTH"}`만 전달한다. decisions는 금지된다. provider URL에 prompt=login이 포함되어야 하며 동일 계정 성공 후 5분간만 개인정보 처리 동의 철회가 가능하다. 일반 LOGIN은 이를 충족하지 않는다. 알려진 카카오 인앱 브라우저는 거절하고 외부 지원 브라우저에서 진행한다.

다른 쿠키의 state, 재사용 callback, 만료된 state, 다른 계정 REAUTH, OAuth 대기 중 세션 교체를 확인한다. 외부 호출 전 선점과 호출 후 DB 재검증을 수행하므로 같은 code를 반복 교환해서는 안 된다.

| 시나리오 | 기대 결과 |
|---|---|
| 필수 동의 거절·잘못된 문서 버전 | OAuth 시작 거절 |
| 유효 state와 시작 쿠키로 callback 취소 | 303 `/login?reason=cancelled` |
| 전달된 state의 만료·재사용·시작 쿠키 불일치 | 303 `/login?reason=oauth_failed` |
| state 누락, state/code/error 중복 파라미터 | 400 오류 응답 |
| REAUTH의 다른 계정 | 403 REAUTH_ACCOUNT_MISMATCH, 계정 교체 없음 |
| 알려진 카카오 인앱에서 REAUTH 시작 / callback | 시작은 403, callback은 303 `/login?reason=external_browser_required` |
| 일반 LOGIN 후 개인정보 처리 동의 철회 | REAUTHENTICATION_REQUIRED |

303은 브라우저 주소 이동 과정에서 확인한다. callback을 fetch로 직접 호출하면 기본 리다이렉트 추적으로 최종 SPA 응답이 보일 수 있다. code를 다시 호출해 재현하지 말고 개발자 도구 Network에서 기존 이동 요청을 확인한다. 공유 기록에는 쿼리와 쿠키를 남기지 않는다.

A04 `/auth/logout`은 `{}`와 현재 CSRF로 호출하여 204를 확인한다. 현재 브라우저의 세션·열린 통화·grant를 정리한다. A03 refresh, accessToken, refreshToken, 설치 ID 기반 인증은 없다.

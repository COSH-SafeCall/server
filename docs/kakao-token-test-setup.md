# 카카오 웹 OAuth 설정 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [환경 설정](environment-setup.md)

현재 API는 서버에서 authorization code를 교환한다. 실제 credential을 브라우저 입력 폼이나 문서에 복사하지 않는다. 기존 파일명은 링크 호환용으로 유지한다.

## 등록과 확인

1. 카카오 앱에 웹 서비스 주소와 정확한 callback URL을 등록한다.
2. 서버의 KAKAO_APP_ID는 숫자 앱 ID, KAKAO_CLIENT_ID는 REST API 키, KAKAO_CLIENT_SECRET은 활성화된 secret이다. 실제 값은 로컬 `.env` 또는 운영 secret 주입으로 관리한다.
3. WEB_ORIGIN과 KAKAO_REDIRECT_URI의 origin을 동일하게 맞춘다. 경로는 `/api/v1/auth/kakao/callback`이다. 개발 HTTPS 주소를 쓰면 카카오 등록 URL도 같은 값이어야 한다.
4. 필요한 프로필 항목의 이용 권한과 동의 scope를 검수하여 KAKAO_LOGIN_SCOPES를 설정한다. 사용자가 제공하지 않은 이름/전화번호는 PROFILE 단계에서 확인한다.
5. A05 초기화 후 서비스 문서 동의 3종을 A02 LOGIN에 보낸다. 반환 URL로 이동하고 callback 완료 뒤 A05로 새 CSRF를 읽는다.
6. REAUTH는 `{purpose: "REAUTH"}`이며 prompt=login을 사용한다. 동일 계정 확인에 성공한 경우에만 민감 작업 시각을 기록한다. 인앱 브라우저는 지원 외부 브라우저로 안내한다.

## 리다이렉트 구성

API callback 경로는 서버 Controller가 처리해야 한다. 프론트엔드 SPA fallback이 먼저 처리하면 code 교환과 쿠키 교체가 수행되지 않는다. callback 후 `/onboarding/profile`, `/settings`, `/login`은 SPA가 제공할 화면 경로이며 서버 API가 HTML을 제공하는 경로가 아니다.

실제 지원 브라우저에서 cookie 수용, 로그인/취소, 계정 불일치와 REAUTH를 확인한다. 알려진 카카오 인앱 User-Agent 거절만으로 모든 브라우저의 지원 여부가 입증되는 것은 아니다. 요청·응답 시나리오는 [인증 가이드](auth-session-onboarding-swagger-test.md)에서 관리한다.

code/state/token/쿠키/프로필을 콘솔·access log·공유 캡처에 기록하지 않는다. callback 쿼리 로깅과 APM 본문 수집은 배포 계층에서도 제외해야 한다.

공식 계약은 [카카오 로그인 REST API](https://developers.kakao.com/docs/en/kakaologin/rest-api)를 참고한다. 실제 앱의 scope 승인, 계정 선택, prompt 동작은 배포할 브라우저에서 별도로 검수한다.

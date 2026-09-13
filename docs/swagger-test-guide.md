# 웹 API 검증 가이드 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [환경 설정](environment-setup.md), [DB 설치](local-db-setup.md)

Swagger/OpenAPI는 경로·DTO·필수 헤더 확인에 사용한다. HttpOnly 쿠키는 Swagger Authorize 입력으로 설정할 수 없으므로 같은 origin의 브라우저에서 로그인한 뒤 쿠키가 자동 전송되는 환경을 사용한다. Origin과 Cookie는 브라우저가 관리하며 JavaScript에서 임의 설정하지 않는다.

서버의 Secure/`__Host-` 쿠키가 실제 수용되는 HTTPS 개발 주소를 권장한다. localhost 예외 동작은 브라우저별로 검수한다. HTTP IP 주소에서 쿠키가 저장되지 않는 문제를 보안 속성 제거로 해결하지 않는다. 리버스 프록시를 쓴다면 페이지와 `/api`를 `WEB_ORIGIN` 하나로 제공한다.

## 시작

같은 origin 페이지의 개발자 도구에서 다음을 실행한다. 반환된 CSRF와 페이지 키는 현재 페이지 메모리에만 둔다.

이하 경로는 `/api/v1`을 생략한다. 셸 명령의 기준 디렉터리는 `server`다. 문서의 JSON 예시는 합성 값이며 ID·version·문서 버전은 실제 응답에서 읽는다.

```javascript
let csrfToken;
async function refreshSession() {
  const response = await fetch('/api/v1/auth/session', {credentials: 'same-origin'});
  if (!response.ok) throw new Error(`Session: ${response.status}`);
  const session = await response.json();
  csrfToken = session.csrfToken;
  return session;
}
async function writeApi(method, path, body, idempotencyKey, pageKey) {
  const headers = {'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken};
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  if (pageKey) headers['X-Call-Page-Key'] = pageKey;
  return fetch('/api/v1' + path, {method, credentials: 'same-origin', headers, body: JSON.stringify(body)});
}
async function readApi(path, pageKey, extraHeaders = {}) {
  const headers = {...extraHeaders};
  if (pageKey) headers['X-Call-Page-Key'] = pageKey;
  return fetch('/api/v1' + path, {credentials: 'same-origin', headers});
}
await refreshSession();
```

A05는 허용 Origin/Referer 또는 same-origin Fetch Metadata를 확인한다. 주소창 단독 진입과 출처 헤더가 없는 curl 호출은 초기화 방법으로 사용하지 않는다. 쿠키를 출력하거나 복사하지 않는다.

ETag 재조회는 첫 응답의 ETag를 읽고 `readApi(path, undefined, {'If-None-Match': etag})`로 보낸다. OAuth로 페이지를 이동하면 함수와 CSRF 메모리도 사라지므로 돌아온 페이지에서 함수를 다시 준비하고 A05를 읽는다.

## 요청 규칙

- 변경 요청에는 Origin과 `X-CSRF-Token`이 필요하다. 쿠키 없는 로그아웃은 CSRF 예외다.
- `Idempotency-Key`는 A07, U02/U05/U06/U08/U09/U10/U12, C01/C04/C06/C07에 UUID 형식으로 전송한다. 같은 논리 작업의 재시도에는 같은 키와 본문을 사용한다.
- C01~C07에는 32바이트 난수의 padding 없는 base64url 페이지 키가 필요하다. 43자 문자열이며 새 페이지에는 새 키를 생성한다.
- 예상하지 않은 JSON 필드, 중복 필드, 누락된 필수 필드, 잘못된 enum을 거절한다. PATCH도 명세에 정한 전체 필드를 전달한다.
- optimistic version 충돌은 409다. 현재 리소스를 다시 읽어 새 사용자 동작으로 전송한다.
- 공개 문서 및 통화 선택지 ETag 검사는 각각 U03/H02 가이드를 따른다.

## 응답 확인

writeApi/readApi는 Response를 반환한다. `status`를 확인한 뒤 JSON 응답만 `json()`으로 읽는다. 204와 304에는 JSON 본문이 없다. 수동 확인을 위해 실패한 변경 요청을 새 키로 자동 재시도하지 않는다.

| 응답 | 확인할 내용 |
|---|---|
| 400 / 422 | JSON 구조·누락·조건부 필드와 업무 값 검증. `code`로 원인을 구분 |
| 401 | 쿠키와 만료 확인 후 A05로 상태 재조회 |
| 403 | Origin/CSRF, 회원 기능, 동의, 최근 REAUTH 조건 |
| 404 | 리소스 ID와 현재 세션·페이지 소유권 |
| 409 | 상태·version·멱등 충돌. REQUEST_IN_PROGRESS면 Retry-After를 따르고, 그 외에는 현재 상태를 조회해 다음 동작 결정 |
| 410 | grant의 새 연결 시작 기한 경과. 기존 token으로 다시 연결하지 않음 |
| 429 | Retry-After 대기. 같은 작업이면 키와 본문 유지 |
| 503 | 외부 연동·발행/검수 설정·발급 결과 불명 여부 |

## 확인 순서

세부 시나리오: [인증·온보딩](auth-session-onboarding-swagger-test.md), [사용자·동의](user-profile-consent-contacts-settings-swagger-test.md), [통화](ai-safety-call-swagger-test.md), [안심 메시지](safety-message-swagger-test.md).

실제 브라우저 결과에는 OS·브라우저 버전, 검수 시각, 시나리오와 성공/실패만 남긴다. cookie/code/state/token/개인정보가 포함된 응답이나 화면은 공유 기록에서 제외한다. 자동 검사와 수동 검사의 경계는 [검증 문서](verification.md)를 참고한다.

# Swagger 테스트 시작

[문서 목록](../README.md) · [API 요청·응답·오류 명세](../API_명세서.md) · 먼저 [환경 설정](../setup.md)과 [DB 설치](../database.md)를 마친다.

## 1. Swagger와 세션 준비

1. 서버를 실행하고 **WEB_ORIGIN과 같은 origin**의 `/swagger-ui/index.html`을 연다. OpenAPI 원문은 `/v3/api-docs`다.
2. A05 `GET /api/v1/auth/session`을 **Try it out → Execute**로 호출한다. 익명 세션이 만들어지고 응답의 `csrfToken`을 받는다.
3. 이후 변경 요청의 `X-CSRF-Token`에 현재 값을 넣는다. `Cookie`와 `Origin`은 브라우저가 자동 전송한다. Swagger에 Origin 입력칸이 보여도 출처를 임의 변경하는 용도로 사용하지 않는다.
4. 게스트 진입·로그인·재인증으로 쿠키가 바뀌면 **A05를 다시 실행**하고 CSRF도 교체한다.

Authorize로 HttpOnly 쿠키를 붙여 넣지 않는다. JWT·accessToken·refreshToken 입력 단계는 없다. Secure/`__Host-` 쿠키가 수용되는 HTTPS 개발 주소를 사용한다. HTTP localhost 예외는 브라우저별로 확인하며, HTTP IP 주소에서 실패할 때 보안 속성을 제거하지 않는다. A05는 출처를 검사하므로 주소창 단독 이동이나 출처 헤더 없는 curl로 초기화하지 않는다.

## 2. 테스트 순서

화면 그룹은 `01. 인증·세션`부터 `11. 이용 기록·데이터 삭제`까지 고정한다. 그룹 안은 API 번호순이며 인증만 A05를 맨 위에 둔다. 번호는 찾아보기 순서이고 실제 테스트는 아래 흐름을 따른다. 통화 생성 직후 C05 유지, 회원 동의·프로필·연락망 저장은 해당 그룹 설명을 참고한다.

| 순서 | 시나리오 | 완료 기준 |
|---|---|---|
| 1 | [인증·온보딩](auth.md) | 게스트 진입 또는 회원 OAuth 로그인 |
| 2 | [사용자·홈](users.md) | 회원 프로필·보호자·설정 확인. 온보딩 PROFILE/CONTACTS에서 먼저 수행 가능 |
| 3 | [메시지](messages.md) | 회원 M01 200, 수신자·마스킹·만료 확인 |
| 4 | [통화](calls.md) | C01 생성 → C03 발급 → C06 종료. 실제 음성은 Live 클라이언트 필요 |
| 5 | [운영 사건](telemetry.md) | O01 신규 접수와 같은 eventId 중복 확인 |
| 6 | [이력·철회·삭제](history-deletion.md) | 완료 이력 조회 → 이력 삭제 → 선택 동의 철회 → ACCOUNT 삭제를 마지막에 확인 |

게스트는 회원 프로필·메시지·이력·삭제를 건너뛴다. 온보딩에서 M01을 테스트할 때는 TEST 모드를 사용한다. 기능을 차단하는 동의 철회·계정 삭제는 다른 테스트를 마친 테스트 계정에서 진행한다.

## 3. 요청값 관리

이하 문서 경로는 `/api/v1`을 생략한다. JSON은 예시이며 `id`, `version`, 문서 버전은 반드시 직전 실제 응답으로 바꾼다.

| 값 | 사용 규칙 |
|---|---|
| `X-CSRF-Token` | 모든 변경 요청에 현재 A05 값. 쿠키 없는 로그아웃만 예외 |
| `Idempotency-Key` | U02/U05/U06/U08/U09/U10/U12, C01/C04/C06, R02에 UUID. 새 작업은 새 키, 같은 작업 재시도는 같은 키·본문 |
| `X-Call-Page-Key` | C01~C06에 같은 페이지의 32바이트 난수 base64url 키. 요청마다 재생성하지 않음 |
| `expectedVersion` | 해당 리소스의 최신 version. 409 충돌 시 다시 읽고 사용자 동작을 재판단 |
| `eventId` | C04/O01 사건의 UUID. 재전송에도 같은 값·본문 유지 |
| `occurredAt` | 시간대가 있는 RFC3339 문자열. 예: `new Date().toISOString()` |

PATCH도 DTO에 정한 필드는 모두 보낸다. 명시적 null과 필드 누락을 구분한다. O01은 Idempotency-Key가 없으며 각 eventId로 중복을 처리한다.

## 4. 콘솔에서 호출할 때

Swagger 입력 반복이나 heartbeat가 필요하면 같은 origin 페이지의 개발자 도구에서 아래 함수를 한 번 준비한다. OAuth 이동 후에는 함수를 다시 준비한다. 민감 응답을 console.log 하거나 브라우저 저장소에 보관하지 않는다.

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
  return fetch('/api/v1' + path, {
    method, credentials: 'same-origin', headers, body: JSON.stringify(body)
  });
}
async function readApi(path, pageKey, extraHeaders = {}) {
  const headers = {...extraHeaders};
  if (pageKey) headers['X-Call-Page-Key'] = pageKey;
  return fetch('/api/v1' + path, {credentials: 'same-origin', headers});
}
void await refreshSession();
```

함수는 Response를 반환한다. 먼저 `response.status`를 확인하고 JSON 응답만 `response.json()`으로 읽는다. 204·304에는 본문이 없다. ETag는 응답 헤더에서 읽고 `readApi(path, undefined, {'If-None-Match': etag})`로 재조회한다.

## 5. 실패 시 확인

| 상태 | 확인 |
|---|---|
| 400 / 422 | 응답 code, 필수·추가 필드, enum·시각·업무 값 |
| 401 | 쿠키 수용·만료, A05 상태 |
| 403 | Origin·CSRF, 회원·동의·프로필·재인증 조건 |
| 404 | ID 및 세션·페이지 소유권 |
| 409 | 현재 상태·version·멱등 충돌. REQUEST_IN_PROGRESS는 Retry-After 준수 |
| 410 | 연결 grant의 시작 기한 만료. 이전 token 재사용 금지 |
| 429 | Retry-After 대기. 같은 작업의 키·본문 유지 |
| 503 | 외부 연동·프롬프트 발행·검수 설정 |

실패한 변경을 새 키로 자동 반복하지 않는다. 결과에는 시나리오·HTTP 상태·오류 code만 기록한다. 자동 검사와 실연동의 경계는 [검증 문서](../verification.md)를 참고한다.

# AI 통화 C01~C07

[테스트 시작](README.md) · 온보딩 COMPLETE와 [검수된 Gemini·프롬프트](../setup.md#문서와-프롬프트-발행) 필요

## 1. 페이지 키 준비

현재 페이지에서 한 번 생성하여 C01~C07의 `X-Call-Page-Key`로 사용한다. 같은 통화의 요청마다 새 키를 만들지 않는다. 값은 페이지 메모리에만 둔다.

```javascript
const callPageKey = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
```

다른 세션·페이지 키로 접근하면 404다. 새로고침하면 새 페이지이므로 이전 통화 키나 재개 handle을 복구해 재사용하지 않는다.

## 2. 생성 → 생존 신호 → 발급 조회

1. C01 `POST /calls`에 아래 본문, 현재 CSRF·새 Idempotency-Key·페이지 키를 보낸다. clientCallId도 새 UUID로 바꾼다.
2. 202의 `id`, `version`, `leaseExpiresAt`, `expiresAt`을 읽는다.
3. **즉시 C05 heartbeat를 시작**하고 생성·발급 대기·연결·통화·재개 동안 5초마다 유지한다.
4. C03 `GET /calls/{id}/connection`을 조회한다. 202이면 `retryAfterMs` 뒤 재조회하며 200이면 연결 정보가 준비된 것이다. 단기 token은 페이지 메모리에서만 사용한다.

```json
{
  "clientCallId":"11111111-1111-4111-8111-111111111111",
  "startMode":"QUICK",
  "scenarioCode":"FOLLOWED",
  "counterpartCode":"FATHER",
  "microphonePermission":"GRANTED"
}
```

Swagger는 heartbeat·마이크·Gemini WSS를 자동 실행하지 않는다. 서버 발급·종료만 확인할 경우 200 발급 확인 뒤 C06으로 종료한다. 실제 CONNECTED·ANSWERED·RESUMED 성공 검수에는 Live 클라이언트가 필요하며 사건을 수동 입력한 것만으로 음성 연결 성공이라 기록하지 않는다.

콘솔에서 [공통 함수](README.md#4-콘솔에서-호출할-때)를 준비했다면 다음으로 heartbeat를 보낼 수 있다. **callId와 callPageKey는 C01에서 사용한 실제 값**이어야 한다.

```javascript
const callId = 'C01 응답의 id';
let heartbeatTimer;
let heartbeatStopped = false;
function stopHeartbeat() {
  heartbeatStopped = true;
  clearTimeout(heartbeatTimer);
}
async function beat() {
  if (heartbeatStopped) return;
  try {
    const response = await writeApi('POST', `/calls/${callId}/heartbeat`, {}, undefined, callPageKey);
    if (!response.ok) { stopHeartbeat(); return; }
  } catch {
    stopHeartbeat();
    return;
  }
  if (!heartbeatStopped) heartbeatTimer = setTimeout(beat, 5000);
}
window.addEventListener('pagehide', stopHeartbeat, {once: true});
document.addEventListener('visibilitychange', () => { if (document.hidden) stopHeartbeat(); });
void beat();
```

이 도우미는 테스트용 생존 신호만 담당한다. 실패하면 상태를 조회하여 다음 동작을 판단한다. 페이지를 숨겨 중단한 이전 통화를 이 코드로 자동 복구하지 않는다.

## 3. 실제 연결과 사건

실제 연결·표시·응답이 발생할 때 C04 `POST /calls/{id}/events`를 순서대로 보낸다. 각 사건은 새 eventId·Idempotency-Key와 최신 CallView version을 사용한다. 전송 재시도는 기존 키·본문 그대로다.

| type | 조건부 필드 | 순서 |
|---|---|---|
| CONNECTED | C03의 grantId | 실제 초기 연결 후 |
| RINGING_SHOWN | grantId 없음 | 수신 화면 표시 후 |
| ANSWERED | grantId 없음 | 응답 후 ACTIVE 진입 |
| GO_AWAY / CONNECTION_INTERRUPTED | grantId 없음 | ACTIVE에서 해당 사건 관측 시 |
| RESUMED | 새 grantId | 같은 통화 재개 성공 후 |
| FAILED | 허용된 errorCode 필수 | 실제 실패 시 |

C04 필수 필드는 eventId, type, occurredAt, expectedVersion이다. `occurredAt`은 시간대 포함 RFC3339 문자열이다. version은 C02 `GET /calls/{id}` 또는 직전 C04 응답에서 읽는다. C05는 version을 변경하지 않는다. 허용 errorCode는 CONNECTION_FAILED/RINGING_FAILED/MICROPHONE_FAILED/AUDIO_FAILED/CONNECTION_LOST/RESUMPTION_FAILED이며 원문 오류를 보내지 않는다.

## 4. 재개 C07

ACTIVE 통화에서 재개 가능한 handle을 실제 수신했고 GoAway/연결 중단이 발생한 경우에만 진행한다.

1. C07 `POST /calls/{id}/connection-renewals`에 previousGrantId, reason(GO_AWAY 또는 CONNECTION_INTERRUPTED), isResumable=true를 보낸다. 새 Idempotency-Key를 사용한다.
2. 202의 새 grantId/generation을 읽고 C03 `/calls/{id}/connection?grantId=...`을 조회한다.
3. Live 클라이언트가 메모리의 handle과 새 token으로 재개한다. handle은 서버 API에 보내지 않는다.
4. 실제 성공 후 새 grantId로 C04 RESUMED를 보낸다. 기존 answeredAt과 총 expiresAt은 유지한다.

재개 예산은 성공·실패 합계 기본 1회다. 최초 개인화·모델·음성·안전 지침을 재구성해 HMAC이 같을 때만 발급하며, 변경되거나 검증 정보가 없으면 RESUMPTION_FAILED로 종료한다. 전체 프롬프트와 handle은 DB에 저장하지 않는다.

## 5. 종료 C06

C06 `POST /calls/{id}/end`에 아래 본문과 현재 CSRF·새 Idempotency-Key·페이지 키를 보낸다. occurredAt은 실행 시각으로 바꾼다. 클라이언트는 서버 응답을 기다리지 않고 소켓·마이크·재생과 `stopHeartbeat()`를 먼저 중지한다.

```json
{"reason":"USER_ENDED","occurredAt":"2026-09-14T09:00:00Z"}
```

200의 종료 상태를 확인하고 회원은 [R01 이용 기록](history-deletion.md)에서 조회한다. 종료된 통화는 재개할 수 없다.

## 경계 확인

| 시나리오 | 기대 결과 |
|---|---|
| 같은 C01 키·본문 재전송 | 기존 callId·현재 상태, 추가 사용량 없음 |
| 같은 C07 키 재전송 | 진행 가능한 통화에서 같은 grantId |
| 새 키로 같은 이전 세대 재개 요청 | RENEWAL_ALREADY_REQUESTED |
| 재개 예산 소진 | RESUME_BUDGET_EXHAUSTED |
| 이전 grant로 CONNECTED/RESUMED | STALE_CONNECTION_GENERATION |
| lease·총 시간 만료 후 heartbeat | 종료 유지, 부활 불가 |
| 사용한 grant 조회 | CONNECTION_ALREADY_USED |
| 미사용 grant의 시작 기한 경과 | 410 CONNECTION_GRANT_EXPIRED |
| 발급 결과 불명 | CONNECTION_ISSUE_UNKNOWN, 같은 grant 외부 재발급 없음 |

사용·종료 시 DB tokenCipher/keyRef를 제거하고 실제 키는 커밋된 폐기 작업으로 정리한다. 장애·동시성·롤백은 [자동 검증](../verification.md), 실제 음성·재개는 [배포 검수](../deployment.md)에서 확인한다.

# AI 통화 검증 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [공통 요청](swagger-test-guide.md), [통화 설정·발행](ai-safety-call-reference-review.md)

온보딩 완료, 회원 필수 동의, 발행된 12개 persona, 실제 검수된 모델/API/voice/resumption 조합이 필요하다. synthetic 검수값은 자동 테스트 전용이다. [공통 브라우저 요청 규칙](swagger-test-guide.md)을 적용한다.

## 페이지 키와 수명

아래 키를 현재 페이지에서 한 번 생성해 C01~C07의 X-Call-Page-Key로 보낸다. 요청마다 생성하지 않는다. 다른 페이지 키나 다른 세션의 리소스 요청은 404다. 쿠키가 같아도 페이지 키가 다르면 소유권이 일치하지 않는다.

```javascript
const callPageKey = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
```

callPageKey와 재개 handle은 현재 페이지 메모리에만 둔다. 새로고침·이탈 후 이전 통화에 재사용하지 않는다. 서버에는 키의 HMAC만 저장된다. 아래 경로는 `/api/v1` 접두사를 생략한다.

## 정상 통화와 재개

1. C01 `POST /calls`에 clientCallId(UUID), startMode=QUICK, scenarioCode=FOLLOWED, counterpartCode=FATHER, microphonePermission=GRANTED를 보낸다. 202의 id/expiresAt/leaseExpiresAt/policyVersion/maxResumeAttempts/resumeDelayMs를 확인한다.
2. 생성 직후 C05 `/calls/{id}/heartbeat`에 `{}`를 5초 간격으로 전송하기 시작한다. C03 대기·연결·통화·재개 중에도 유지하며 종료/이탈 시 중단한다. lease는 30초, 전체 상한은 최대 600초다.
3. C03 `/calls/{id}/connection`은 발급 중 202와 retryAfterMs, 준비되면 200이다. 안내된 간격으로 재조회하고 단기 token은 페이지 메모리에만 보관한다. GET 재시도는 새 token을 발급하지 않는다.
4. 실제 연결 후 C04에 eventId, type=CONNECTED, grantId, occurredAt, expectedVersion을 전송한다. 다음 RINGING_SHOWN → ANSWERED에는 grantId를 넣지 않는다. 각 요청은 최신 version을 사용한다.
5. ACTIVE 중 GoAway/연결 중단을 C04의 GO_AWAY/CONNECTION_INTERRUPTED로 관측할 수 있다. 재개 handle과 resumable 상태는 브라우저 메모리에만 유지한다.
6. C07에 previousGrantId, reason(GO_AWAY/CONNECTION_INTERRUPTED), isResumable=true를 보낸다. 202에서 새 grantId/generation을 읽고 C03의 grantId 쿼리로 연결 정보를 받는다. 재개 성공 C04는 RESUMED와 새 grantId를 사용한다.
7. 통화 전체 재개 예산은 성공/실패를 합쳐 1회다. ACTIVE 상태와 기존 answeredAt/최대 expiresAt를 유지하며 초기 프롬프트를 다시 계산하지 않는다. handle은 서버에 보내지 않는다.
8. 이탈/숨김/종료 시 브라우저에서 소켓·마이크·재생을 즉시 정리하고 C06에 reason과 occurredAt을 보낸다. 서버 응답을 기다리지 않는다. 종료된 통화는 재개할 수 없다.

C04의 `expectedVersion`은 C02 또는 직전 CallView 응답에서 읽는다. C05는 version을 반환하거나 변경하지 않는다. 서버 API만 직접 호출할 때도 heartbeat를 담당할 검증 클라이언트가 필요하며, 이 문서의 요청 함수는 주기 전송·마이크·WSS를 자동 실행하지 않는다. 수동 사건 입력만으로 실제 연결 성공을 기록하지 않는다.

## 경계·실패 확인

| 시나리오 | 기대 결과 |
|---|---|
| 같은 C01 키·본문 재시도 | 기존 callId와 현재 상태, 추가 통화/사용량 소비 없음 |
| 같은 C07 키 재시도 | 진행 가능한 통화에서 같은 grantId |
| 다른 키로 같은 이전 세대 재요청 | RENEWAL_ALREADY_REQUESTED |
| 재개 1회 사용 후 추가 시도 | RESUME_BUDGET_EXHAUSTED |
| 이전 grant의 CONNECTED/RESUMED | STALE_CONNECTION_GENERATION |
| lease·전체 통화 한도 경과 | 종료 처리, heartbeat로 부활 불가 |
| 사용 완료 grant 조회 | CONNECTION_ALREADY_USED |
| 미사용 grant의 새 연결 시작 기한 경과 후 조회 | 410 CONNECTION_GRANT_EXPIRED |
| 불명확한 발급 | CONNECTION_ISSUE_UNKNOWN, 같은 grant 외부 재발급 없음 |

FAILED 사건의 errorCode는 명세 허용값만 사용한다. C04에 원문 오류·음성·대화·좌표를 넣지 않는다. grant 발급 결과 불명은 UNKNOWN이고 같은 grant를 재발급하지 않는다. DB에 tokenCipher가 있어도 사용/종료 후 NULL이 되고 임시 키는 커밋 뒤 폐기되어야 한다.

실제 브라우저 검수에는 쿠키, 마이크 권한, 오디오 재생, WSS 접속, 탭 이탈, 소켓 중단, GoAway 재개, 최대 통화 상한, 로그 개인정보 유출 여부를 포함한다. 자동 테스트는 서버 상태·정책·HTTP 계약을 확인하며 실제 provider 및 브라우저 동작을 증명하지 않는다.

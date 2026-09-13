# 운영 관측 사건 검증

[문서 목록](README.md) · [공통 요청 방법](swagger-test-guide.md) · v4.2-web-mvp API 7장

## O01 호출

게스트 또는 카카오 회원 세션에서 `POST /api/v1/telemetry/events`를 호출한다. 온보딩 완료 여부와 무관하게 권한 안내 등의 사건을 보낼 수 있다. Origin과 현재 세션의 X-CSRF-Token이 필요하며 Idempotency-Key와 X-Call-Page-Key는 필요하지 않다.

공통 가이드의 `writeApi`를 준비하고 로그인 또는 게스트 진입 후 A05로 CSRF를 갱신한다.

```javascript
const event = {
  eventId: crypto.randomUUID(),
  callId: null,
  category: 'MESSAGE_COMPOSER',
  code: 'COMPOSER_OPENED',
  isSuccess: true,
  latencyMs: 320,
  networkType: 'UNKNOWN',
  occurredAt: new Date().toISOString()
};
const response = await writeApi('POST', '/telemetry/events', {events: [event]});
const counts = await response.json();
// 202: {acceptedCount: 1, duplicateCount: 0}
// 같은 event를 재전송하면 202: {acceptedCount: 0, duplicateCount: 1}
```

예시 값은 합성 값이다. 실제 브라우저에서는 내부 작성 화면 표시 사건을 계측하며 문자 발송 성공으로 해석하지 않는다. SOS_GUIDE_VIEWED 역시 안내 표시만 뜻한다.

## 입력과 성공 의미

- events는 1~20개이며 eventId(UUID), category, code, occurredAt이 필수다. callId, isSuccess, latencyMs, networkType은 생략 또는 null이 가능하다.
- latencyMs는 0~3600000의 정수만 허용한다. 숫자 문자열, 소수, boolean 문자열을 자동 변환하지 않는다.
- occurredAt은 시간대가 포함된 RFC3339 문자열이다. 숫자 epoch와 로컬 시각은 거절한다. MySQL 범위인 UTC 1000~9999년을 허용하고, 저장·중복 비교에는 DATETIME(6)에 맞춘 마이크로초 정밀도를 사용한다. 7~9번째 소수 자릿수는 버린다. 동일 순간의 다른 시간대 표기는 중복이다.
- networkType은 WIFI/CELLULAR/OFFLINE/UNKNOWN만 허용한다. 조회 미지원은 UNKNOWN으로 전송한다.
- category/code 쌍은 [최종 API 허용목록](../../design/API_명세_최종.md#telemetry)을 따른다. AUTH/AUTH_SUCCEEDED는 서버 전용으로 O01에서 거절한다.
- 성공 여부를 모르면 null로 전송한다. 아래의 명확한 성공·실패 코드에 반대 boolean을 보내면 422 INVALID_EVENT다. 시작·페이지 종료·권한 검토 등 나머지 코드는 true/false/null을 허용하며 그 값으로 서버 상태를 확정하지 않는다.

| isSuccess가 null이 아닐 때 | code |
|---|---|
| true | LIVE_CONNECT_SUCCEEDED, LIVE_RESUME_SUCCEEDED, RINGING_SHOWN, FIRST_AUDIO_PLAYED, LOCATION_AVAILABLE, COMPOSER_OPENED, SOS_GUIDE_VIEWED |
| false | PERMISSION_QUERY_UNAVAILABLE, LIVE_CONNECT_FAILED, LIVE_RESUME_FAILED, RINGING_FAILED, LOCATION_UNAVAILABLE, COMPOSER_OPEN_FAILED, SOS_GUIDE_FAILED |

AUDIO_INTERRUPTED는 사용자 끼어들기에 따른 정상 중단도 포함하므로 true/false/null을 허용한다.

sessionId/userId/webVersion은 입력할 수 없다. 배포 버전은 서버 WEB_VERSION에서만 읽는다. 음성·대화·이름·전화번호·좌표·accuracy·URL·토큰·handle·임의 payload·errorMessage·브라우저 지문 등 선언하지 않은 필드는 400이다. 검증 응답에 거절한 값을 반사하지 않는다.

## 중복·한도·원자성

동일 세션/eventId의 모든 허용 입력을 비교한다. nullable 필드의 생략과 null은 같다. 배포 WEB_VERSION·recordedAt은 비교 대상이 아니므로 배포 후 재전송도 중복이다. 같은 ID에 다른 허용 내용은 409 IDEMPOTENCY_CONFLICT이며 같은 배치 안에서도 적용한다. 서로 다른 세션은 같은 eventId를 독립적으로 사용할 수 있다.

서버 UTC 분 경계마다 세션별 신규 사건 120건을 허용한다. 한 배치의 신규 사건 수만 차감하고 중복은 한도가 찬 뒤에도 재전송할 수 있다. 초과 시 429와 다음 분까지의 Retry-After를 반환한다. 예를 들어 신규 120건 접수 후 중복 1건+신규 1건 배치는 전체 거절된다.

계정→세션 잠금을 유지하는 트랜잭션 안에서 전체 검증·중복 비교·한도 차감·저장을 처리한다. 동시 요청은 직렬화되며 잘못된 사건, 소유권 오류, 내용 충돌, 한도 초과 또는 DB 저장 실패가 있으면 배치의 신규 행과 한도 차감이 모두 롤백된다.

callId는 현재 웹 세션 소유여야 한다. 같은 회원의 다른 브라우저 세션도 사용할 수 없으며 존재하지 않거나 다른 세션 소유면 404다. 종료된 통화도 남아 있고 소유권이 맞으면 관측할 수 있다. 성공 사건을 보내도 통화 상태·grant·재개 예산·인증·온보딩은 변경되지 않는다.

## 삭제·보존 및 검증

operationEvent는 서버 recordedAt 기준 14일 후 정리한다. 과거 occurredAt 사건을 보냈다고 즉시 삭제하지 않는다. 계정/세션 삭제와 관련 통화 삭제가 더 빠르면 FK cascade로 먼저 정리된다. 이용 기록 삭제는 해당 통화에 연결된 사건을 삭제하며 callId 없는 사건은 보존 정책을 따른다. 계정·세션 정리 때 TELEMETRY_RATE HMAC 버킷도 제거한다. 분 버킷 자체는 만료 후 정기 정리된다.

`python scripts/verify_auth.py`는 허용목록·성공 의미·금지 필드, 세션/CSRF/Origin, 통화 소유권, 중복·동시 충돌, 한도와 분 경계, DB 중간 실패 롤백, 삭제·14일 경계와 OpenAPI를 검사한다. 실제 실행 수량과 시각은 [검증 결과](verification.md)를 따른다. 브라우저의 사건 생성 시점·지연 측정·네트워크 전환은 별도 검수한다.

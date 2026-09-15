# 운영 사건 O01

[테스트 시작](README.md) · 게스트 또는 회원 세션과 현재 CSRF 필요. 온보딩 완료는 필수 아님

## 신규 접수 → 중복 → 충돌

O01 `POST /telemetry/events`에 아래 본문을 보낸다. eventId는 새 UUID, occurredAt은 실행 시각으로 바꾼다. Idempotency-Key·페이지 키는 필요 없다.

```json
{
  "events":[{
    "eventId":"22222222-2222-4222-8222-222222222222",
    "callId":null,
    "category":"MESSAGE_COMPOSER",
    "code":"COMPOSER_OPENED",
    "isSuccess":true,
    "latencyMs":320,
    "networkType":"UNKNOWN",
    "occurredAt":"2026-09-14T09:00:00Z"
  }]
}
```

| 순서 | 요청 | 기대 결과 |
|---|---|---|
| 1 | 새 eventId | 202, acceptedCount=1 / duplicateCount=0 |
| 2 | 같은 세션에서 본문 그대로 재전송 | 202, acceptedCount=0 / duplicateCount=1 |
| 3 | 같은 eventId로 latencyMs 변경 | 409 IDEMPOTENCY_CONFLICT, 배치 전체 미반영 |
| 4 | 새로운 eventId에 선언하지 않은 payload 추가 | 400, 배치 전체 미반영 |

이 예시는 합성 사건의 서버 계약 검사다. 실제 COMPOSER_OPENED는 작성 화면 표시를 뜻하며 문자 발송 성공을 뜻하지 않는다. SOS_GUIDE_VIEWED도 안내 표시다.

## 입력 규칙

- events는 1~20개다. eventId/category/code/occurredAt은 필수이며 callId/isSuccess/latencyMs/networkType은 생략 또는 null이 가능하다.
- latencyMs는 0~3600000 정수, networkType은 WIFI/CELLULAR/OFFLINE/UNKNOWN이다. 문자열 숫자·소수·boolean 문자열은 자동 변환하지 않는다.
- occurredAt은 시간대 있는 RFC3339이며 UTC 1000~9999년 범위다. 마이크로초 이후 소수 자릿수는 버리고 같은 순간의 다른 시간대 표기도 중복으로 비교한다.
- category/code는 [TelemetryPolicy 허용목록](../../src/main/java/com/safecall/service/telemetry/service/TelemetryPolicy.java)을 따른다. AUTH/AUTH_SUCCEEDED는 서버 전용이다.
- sessionId/userId/webVersion·음성·대화·이름·전화번호·위치·URL·token·handle·임의 payload/errorMessage는 입력하지 않는다. 배포 버전은 서버 WEB_VERSION에서 읽는다.

| isSuccess가 null이 아닐 때 | code |
|---|---|
| true만 허용 | LIVE_CONNECT_SUCCEEDED, RINGING_SHOWN, FIRST_AUDIO_PLAYED, LOCATION_AVAILABLE, COMPOSER_OPENED, SOS_GUIDE_VIEWED |
| false만 허용 | PERMISSION_QUERY_UNAVAILABLE, LIVE_CONNECT_FAILED, RINGING_FAILED, LOCATION_UNAVAILABLE, COMPOSER_OPEN_FAILED, SOS_GUIDE_FAILED |

그 외 허용 사건은 true/false/null이 가능하다. AUDIO_INTERRUPTED도 정상 끼어들기를 포함하므로 세 값 모두 허용한다. 사건 성공값으로 서버 통화 상태를 변경하지 않는다.

## 소유권·한도·보존

callId는 현재 웹 세션의 통화여야 한다. 같은 회원의 다른 브라우저 통화도 404다. 종료 통화는 데이터가 남아 있고 소유권이 맞으면 관측할 수 있다.

한도는 서버 UTC 분마다 **세션별 신규 120건**이다. 중복은 차감하지 않으며 한도 이후에도 같은 사건을 재전송할 수 있다. 초과 배치는 429와 Retry-After를 반환한다. 하나라도 잘못되거나 충돌·한도 초과이면 신규 행과 한도 차감을 모두 롤백한다.

operationEvent는 recordedAt부터 14일 후 정리한다. 관련 계정·세션·통화 삭제 시 더 일찍 제거될 수 있다. callId 없는 사건은 통화 이력 삭제 대상이 아니다. 동시성·분 경계·DB 실패는 [자동 검증](../verification.md), 실제 사건 발생 시점과 latency 측정은 브라우저 검수 대상이다.

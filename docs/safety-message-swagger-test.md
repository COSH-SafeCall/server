# 안심 메시지 작성 자료 검증 · M01

[문서 목록](README.md) · [공통 요청 방법](swagger-test-guide.md) · v4.2-web-mvp API 5장

## 요청과 응답

`GET /api/v1/message-composer?mode=SAFETY`는 카카오 회원의 현재 작성 자격을 재검증한다. mode 생략은 SAFETY, 온보딩·설정에서 연락망을 확인하는 테스트는 TEST다. 보안 세션 쿠키가 필요하며 조회 요청에는 CSRF·멱등 키가 필요하지 않다. 빈 mode, 중복 mode, 알 수 없는 query와 요청 본문은 400이다.

응답은 `mode`, `recipients`, `identity`, `baseBody`, `templateVersion`, `isLocationConsentGranted`, `mapTemplate`, `notice`, `preparedAt`, `expiresAt`의 10개 필드다. recipients는 U07 ContactView와 같은 6개 필드이며 slot 순으로 보호자 1~2명만 포함한다. 본인은 수신자로 추가하지 않는다.

본인 번호는 `010-xxxx-5678`처럼 가운데 네 자리만 가린다. 일반 본문은 `홍길동(010-xxxx-5678)의 SafeCall 안심 메시지입니다.`, TEST는 `[테스트] ` 접두사다. notice는 `이 화면에서는 실제 문자가 발송되지 않습니다.`다. 초기 templateVersion은 3이다.

preparedAt은 UTC, expiresAt은 준비 시각+5분과 현재 세션 만료 중 더 이른 시각이다. 자료를 복호화하는 동안 세션이 만료되어도 401 SESSION_EXPIRED로 거절하고 세션 만료 처리를 유지한다. 성공·오류 모두 `Cache-Control: no-store`이며 ETag/304를 사용하지 않는다. 클릭마다 새로 조회하고, 실패할 경우 이전 작성 자료로 진행하지 않는다.

## 검수 순서

1. 기존 인증 가이드대로 회원 로그인·개인정보 동의·프로필 확인을 완료한다.
2. U08로 합성 보호자 연락처를 1~2명 등록한다. 실제 사용자 개인정보를 검수 기록에 남기지 않는다.
3. 온보딩이 MESSAGE_TEST일 때 `readApi('/message-composer?mode=TEST')`는 200, SAFETY는 403 ONBOARDING_REQUIRED인지 확인한다.
4. COMPLETE 후 SAFETY/TEST 모두 200인지 확인하고 수신자 순서·마스킹·본문·안내·만료 필드를 확인한다.
5. 프로필 또는 연락처 수정 후 다시 조회하여 이전 응답 대신 최신 이름·번호·version이 나오는지 확인한다.
6. 현재 브라우저에 열린 통화를 만들면 409 CALL_ALREADY_OPEN, 종료 후에는 다시 200인지 확인한다.

## 오류와 경계

| 조건 | 기대 응답 |
|---|---|
| 세션 없음·만료·로그아웃 | 401 SESSION_EXPIRED |
| 익명·게스트 | 403 LOGIN_REQUIRED |
| 개인정보 동의 없음·현재 발행 버전 불일치 | 403 CONSENT_REQUIRED |
| SAFETY에서 COMPLETE 이전, TEST에서 MESSAGE_TEST/COMPLETE 이외 | 403 ONBOARDING_REQUIRED |
| 프로필 이름·번호 누락 또는 미확인 | 409 PROFILE_REQUIRED (프로필 PATCH의 422와 구분) |
| 보호자 없음 | 409 CONTACT_REQUIRED |
| 현재 세션의 열린 통화 | 409 CALL_ALREADY_OPEN |
| ACCOUNT/AI_DATA/LOCATION_DATA 정리 대기 | 409 DATA_CLEANUP_PENDING |
| 비허용 Origin | 403 ORIGIN_NOT_ALLOWED |
| 빈·잘못된·중복 mode, 명세 밖 query·본문 | 400 INVALID_REQUEST |

본문은 Content-Length가 양수이거나 Transfer-Encoding 헤더가 있으면 읽기 전에 거절한다. 전송이 멈춘 본문을 읽으려고 기다리지 않는다.

AI 동의는 M01의 필수 조건이 아니다. AI 정리가 끝난 뒤 개인정보 동의·프로필·연락망 조건을 충족하면 작성할 수 있다. 위치 동의는 선택 사항이며 최신 발행 문서와 마지막 동의가 유효할 때만 true다. 위치 동의 false 자체는 작성 오류가 아니다.

## 일관성·데이터 처리

MessageService는 기존 계정→세션 잠금을 유지한 뒤 MessageRepository의 단일 SELECT로 세션·프로필·최신 동의 문서와 이벤트·보호자·정리 작업·열린 통화를 읽는다. READ_COMMITTED의 같은 문장 스냅샷을 사용하며 복호화 동안 키 폐기를 막는다. 조회 이후 다른 브라우저의 변경을 실시간으로 동기화하는 기능은 아니다.

메시지 행·본문 캐시·멱등 응답·메시지 운영 이벤트를 새로 저장하지 않는다. 만료된 인증 세션의 기존 정리 처리는 적용된다. 원본 본인 번호·성별·생년월일·키 참조는 응답에 넣지 않는다. 자료·identity의 toString도 내용을 숨긴다.

## 지도와 프론트엔드 경계

지도 설정이 없으면 mapTemplate 전체가 null이다. 검수된 배포 설정을 등록하면 `{version,urlTemplate,coordinateSystem:"WGS84",maxAgeSeconds:30,maxAccuracyMeters:100}`를 반환한다. 설정 방법은 [환경 설정](environment-setup.md)을 따른다. 자동 테스트의 합성 URL은 실제 검수 자료가 아니다.

브라우저는 조회 성공 후 유효한 위치 동의와 이미 허용된 권한이 있을 때만 좌표를 한 번 취득한다. 500ms 대기·30초 freshness·100m 정확도 기준을 적용하며 실패·권한 거부·템플릿 미검수 시 위치 링크만 제외한다. 좌표·완성 지도 URL·위치 포함 본문을 서버에 전송하지 않는다.

자료는 현재 React 화면의 메모리에서만 expiresAt까지 사용하고 이탈·변경·로그아웃·만료 시 폐기한다. 홈에서 미리 조회하거나 주기적으로 polling하지 않는다. 실제 작성 화면 1초 목표에는 M01 재검증 시간이 포함된다. 이 서버 테스트는 UI 성능이나 실제 지도 동작 완료를 의미하지 않는다.

`/api/v1/message-composer/location-link`, `/api/v1/send-sms`, `/api/v1/sms/send`는 등록하지 않으며 유효한 웹 요청으로 호출하면 표준 404다. 실제 SMS 발송·문자 앱 호출·SOS 실행 기능은 없다.

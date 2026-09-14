# 메시지 작성 자료 M01

[테스트 시작](README.md) · 회원의 개인정보 동의·프로필 확인·보호자 1명 이상 필요

## 정상 흐름

| 순서 | 호출·동작 | 기대 결과 |
|---|---|---|
| 1 | GET `/message-composer?mode=TEST` | 200, 테스트 본문 |
| 2 | 같은 데이터로 `mode=SAFETY` | 200, 일반 본문 |
| 4 | U02/U09 수정 후 다시 조회 | 최신 이름·번호·version 반영 |
| 5 | 현재 브라우저에 통화를 만들고 조회 | 409 CALL_ALREADY_OPEN. 종료 후 다시 200 |

GET 요청이라 CSRF·멱등 키·본문은 필요 없다. mode 생략은 SAFETY다. 성공·오류 모두 `Cache-Control: no-store`이며 ETag/304를 사용하지 않는다.

## 응답 확인

| 필드 | 확인 |
|---|---|
| mode, templateVersion | 요청 모드, 현재 템플릿 버전 |
| recipients | 보호자 1~2명, slot 순서. 본인을 수신자에 추가하지 않음 |
| identity, baseBody | 본인 번호 가운데 네 자리 마스킹, TEST 본문은 `[테스트] ` 접두사 |
| notice | 실제 문자를 발송하지 않는다는 안내 |
| isLocationConsentGranted, mapTemplate | 유효 위치 동의와 검수된 지도 설정. 미설정 템플릿은 null |
| preparedAt, expiresAt | UTC 준비 시각, 준비+5분과 세션 만료 중 이른 시각 |

클릭마다 새로 조회한다. 실패하거나 만료되면 이전 자료로 진행하지 않는다. AI 동의는 M01의 필수 조건이 아니며 위치 동의가 없어도 위치 없는 작성 자료를 반환한다.

## 실패 확인

| 조건 | 기대 결과 |
|---|---|
| 세션 없음·만료·로그아웃 | 401 SESSION_EXPIRED |
| 익명·게스트 | 403 LOGIN_REQUIRED |
| 개인정보 동의 없음 | 403 CONSENT_REQUIRED |
| 프로필 미확인·이름/번호 없음 | 409 PROFILE_REQUIRED |
| 보호자 없음 | 409 CONTACT_REQUIRED |
| ACCOUNT·AI_DATA·LOCATION_DATA 정리 중 | 409 DATA_CLEANUP_PENDING; ACCOUNT는 세션 단계에서 먼저 차단될 수 있음 |
| 잘못된·빈·중복 mode, 추가 query 또는 본문 | 400 INVALID_REQUEST |

## 서버와 화면의 경계

M01은 작성 자료를 반환하며 메시지 행·좌표·완성 링크를 저장하거나 SMS를 발송하지 않는다. 화면은 자료를 현재 메모리에만 보관하고 이탈·정보 변경·로그아웃·만료 시 폐기한다.

지도는 [검수한 설정](../setup.md#안심-메시지-지도-템플릿)이 있을 때만 제공한다. 실제 위치 취득·본문 조립·화면 표시와 1초 목표는 브라우저 검수 대상이다. 유효 위치 동의·이미 허용된 권한이 있을 때 한 번 취득하며 500ms 대기, 30초 이내·100m 이내 기준을 적용한다. 위치 취득 실패나 미검수 템플릿이면 위치 링크만 제외한다. 좌표·완성 URL·위치 포함 본문은 서버로 보내지 않는다.

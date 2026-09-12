# SafeCall 서버 문서

기준: **v4.2-web-mvp · 2026-09-12**. 공통 설계와 API 1~4장까지의 서버 사용·검증·운영 준비 문서다. 모든 셸 명령은 `server` 디렉터리에서 실행한다. [서버 개요 및 API 목록](../README.md)

## 목적별 읽는 순서

| 목적 | 순서 |
|---|---|
| 처음 실행 | [환경 설정](environment-setup.md) → [DB 설치](local-db-setup.md) → [카카오 OAuth](kakao-token-test-setup.md) |
| 브라우저 API 확인 | [공통 요청](swagger-test-guide.md) → [인증·온보딩](auth-session-onboarding-swagger-test.md) → [사용자·홈](user-profile-consent-contacts-settings-swagger-test.md) → [통화](ai-safety-call-swagger-test.md) |
| 코드 변경 검증 | [자동 검증과 결과](verification.md) → [변경 리포트](web-v4-refactor-report.md) |
| 통화 구현 검토 | [명세·코드 대응](ai-safety-call-reference-review.md) → [통화 가이드](ai-safety-call-swagger-test.md) |
| 변경 제출 | [커밋·PR 작성 예시](ai-safety-call-git-messages.md) |
| 운영 배포 준비 | [배포 조건](aws-deployment-readiness.md) |

## 현재 범위

| 항목 | 상태 |
|---|---|
| 인증·사용자·홈·통화 | 28개 HTTP 작업 구현. A02의 시작과 콜백은 별도 작업으로 집계 |
| DB | 최종 DDL의 19테이블·189컬럼·171제약 적용; 새 DB 설치용 |
| 자동 검증 | [검증 문서](verification.md)와 [결과 JSON](verification-web-v4.json)에서 실행 시각·수량 확인 |
| ACCOUNT 삭제 | 로컬 삭제와 LOCAL_DELETED까지 구현; 외부 연결 정리와 R03 조회는 후속 범위 |
| 5~7장 신규 API | 메시지 작성·이력·삭제 조회·telemetry 후속 범위 |
| 실제 외부 연동·운영 | 카카오/Gemini/브라우저 검수 및 운영 외부 키 저장소 연결 필요 |

## 문서 관리 기준

- 제품 계약은 [최종 API](../../design/API_명세_최종.md), [파라미터](../../design/API_파라미터_정리_및_검토.md), [논리 DB](../../design/DB_논리_설계_최종.md), [물리 DB](../../design/DB_물리_설계_최종.sql), [운영 정책](../../design/최종_운영_정책.md)을 기준으로 한다. 이 폴더는 구현과 실행 방법을 설명한다.
- 환경변수의 입력 양식은 [.env.example](../.env.example), 의미와 설정 순서는 [환경 설정](environment-setup.md)에서 관리한다. 실제 값은 문서에 기록하지 않는다.
- 테스트 수와 실행 시각은 [verification-web-v4.json](verification-web-v4.json)을 기준으로 설명한다. 문서 수정일을 테스트 실행일로 바꾸지 않는다.
- API 가이드는 공통 접두사 `/api/v1`을 생략해 표기한다. 생성 ID·version·grantId·시각은 실제 응답에서 읽는다.
- `swagger`, `token`, `git-messages`가 포함된 기존 파일명은 링크 호환을 위해 유지한다. 내용은 모두 현재 웹 구현 기준이다.

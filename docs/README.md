# 백엔드 문서

SafeCall 웹 MVP API 1~7장. 현재 실행·구조·검증에 필요한 문서만 관리한다.

| 목적 | 문서 |
|---|---|
| 처음 실행 | [환경 설정](setup.md) → [DB 설치·마이그레이션](database.md) |
| 코드 이해 | [서비스 구조·핵심 정책·API 목록](architecture.md) |
| API 수동 확인 | [Swagger 테스트 시작](swagger/README.md) |
| 자동 테스트와 결과 | [검증](verification.md) · [결과 JSON](verification-results.json) |
| 운영 전환 | [배포 조건](deployment.md) |

## Swagger 진행 순서

1. [공통 준비](swagger/README.md): 쿠키·CSRF·요청 규칙 확인.
2. [인증·온보딩](swagger/auth.md): 게스트 또는 카카오 회원으로 진입.
3. [사용자·홈](swagger/users.md): 프로필·연락망·설정 확인. 회원 온보딩 중에도 사용.
4. [메시지](swagger/messages.md) → [통화](swagger/calls.md) → [운영 사건](swagger/telemetry.md) 확인.
5. [이력·동의 철회·삭제](swagger/history-deletion.md): 나머지 검증을 마친 뒤 테스트 계정에서 실행.

## 문서 기준

- 입력 필드와 HTTP 계약은 실행 서버의 `/v3/api-docs`, 제품 의도는 [최종 API](../../design/API_명세_최종.md)와 [운영 정책](../../design/최종_운영_정책.md)를 대조한다.
- DB 기준은 [논리 설계](../../design/DB_논리_설계_최종.md)와 [물리 설계](../../design/DB_물리_설계_최종.sql)다.
- 설정 설명은 setup, 내부 처리 설명은 architecture, 요청 순서는 swagger, 실행 증거는 verification에서 관리한다.
- 과거 수정 경위와 커밋·PR 문안은 Git 이력을 이용한다. 문서 수정만으로 테스트 실행 시각을 갱신하지 않는다.

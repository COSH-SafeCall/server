# 검증

[문서 목록](README.md) · [결과 JSON](verification-results.json)

## 확인된 결과

2026-09-15 정적 서비스 문서 전환 후 코드 검증 결과다.

| 구분 | 단위 | MySQL 통합 | 실패·오류·생략 | 범위 |
|---|---:|---:|---|---|
| 최신 코드 | 62 | 152 | 모두 0 | serviceDocument·U03 제거, 고정 동의 version=1, 총 214개 |
| 기존 DB 이관 | 해당 없음 | 해당 없음 | 적용 성공 | 기존 develop DDL에 20260915 마이그레이션 적용 후 serviceDocument 부재·버전 CHECK 2개 확인 |

`git diff --check`가 통과했고 서버 DDL과 설계 사본이 바이트 단위로 일치한다. 격리 MySQL 8.0.41의 실제 스키마는 **19테이블·188컬럼·165제약**이다.

통합 테스트의 실제 `/v3/api-docs`에서 U03 경로가 제거되고 나머지 보안·응답 계약이 유지됨을 확인했다. 설계 검증은 32개 작업의 중복 없음과 필수 경로를 확인했다.

JSON의 `latest_suite_timestamp`는 각 작업에서 마지막으로 시작한 suite의 UTC 시각이다. 완료 시각이 아니다. 기준 커밋과 함께 미커밋 변경을 검증했으며 정확한 스키마 해시·마이그레이션 목록은 JSON에 있다.

## 실행 방법

`server` 폴더에서 Java 21과 Python을 사용한다.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
python scripts/verify_auth.py
.\gradlew.bat bootJar --no-daemon
python scripts/report_web_verification.py
git diff --check
```

| 명령 | 검사 범위 |
|---|---|
| `verify_auth.py` | 임시 MySQL 생성 → DDL 적용 → 단위·통합 테스트 → 프로세스·데이터 정리 |
| `bootJar` | 실행 JAR 생성 |
| `report_web_verification.py` | DDL 사본 일치와 마지막 Gradle XML 집계. 테스트 자체는 실행하지 않음 |
| `git diff --check` | 변경 파일의 공백 오류 |

MySQL 경로가 다르면 `python scripts/verify_auth.py --mysql-bin "C:/Program Files/MySQL/MySQL Server 8.0/bin"`으로 지정한다. 빠른 단위 검사만 필요하면 `.\gradlew.bat test`를 사용한다.

통합 테스트는 3306을 제외한 임의 포트와 임시 DB·합성 자격 증명을 사용한다. test 프로필은 로컬 `.env`를 읽지 않는다. 격리 DB 증명 없이 `integrationTest`만 직접 실행하지 않는다. 현재 소스와 XML의 최신성을 집계 명령이 보장하지 않으므로 전체 실행 성공을 확인한 후 결과를 갱신한다.

## 자동 검증 범위

| 영역 | 검사 |
|---|---|
| 인증 | 쿠키·CSRF·Origin, OAuth 선점·재검증·응답 제한, 동일 계정 REAUTH, 세션 수명 |
| 사용자·홈 | 입력·소유권·version·멱등, 프로필·연락망, 동의별 자격과 삭제 상태 일치 |
| 통화 | 동시 생성·재개, 페이지 키·grant 세대, 발급 시작 시각, 최초 지침 HMAC, lease·시간 상한·게스트 한도 |
| 메시지 | 최신 수신자·마스킹, 동의·열린 통화·정리 차단, no-store·만료·본문 거절 |
| 이력·삭제 | 사용자별 커서, 확인·재인증·접수증, cutoff, 원자 삭제·롤백, 외부 선점·재시도·실패 작업 뒤 진행 |
| 키·작업자 | 생성 전 복구 기록, 연결 풀 포화, 롤백·재시작 복구, 키 폐기·계정 정리 지연 중 통화 진행 |
| 운영 사건 | 허용목록·개인정보 차단·성공 의미, 세션별 중복·한도, 동시 요청·배치 롤백·14일 보존 |
| 계약 | 실제 MySQL 제약, HMAC 고정 벡터, 모의 HTTP, OpenAPI |

보고서는 실행 후 `build/reports/tests/test/index.html`과 `build/reports/tests/integrationTest/index.html`에 생성된다. XML은 `build/test-results`에 있다.

## 수동 검증 범위

[Swagger 흐름](swagger/README.md)은 서버 요청·응답을 확인한다. 실제 카카오/Gemini, 보안 쿠키 수용, 마이크·음성·WSS·GoAway 재개, 지도와 브라우저 사건 측정은 자동 테스트의 모의 응답으로 증명되지 않는다. 운영 외부 키 저장소·프록시·부하·백업 복원은 [배포 조건](deployment.md)을 따른다.

수동 결과에는 환경·브라우저 버전·시각·시나리오·기대/실제 상태만 남긴다. 쿠키·토큰·OAuth 쿼리·개인정보 응답은 기록하지 않는다.

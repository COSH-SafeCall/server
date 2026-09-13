# 자동 검증과 결과

[문서 목록](README.md) · v4.2-web-mvp

## 기록된 결과

[검증 결과 JSON](verification-web-v4.json)은 API 6장 구현·재검토 후 2026-09-13 KST 실행 기록이다. 단위 47개와 MySQL 통합 107개, 합계 **154개**가 성공했으며 실패·오류·생략은 0이다. 5장까지의 125개에 커서·카카오 연결 해제·불명확한 커밋 처리 단위 테스트 7개와 R01~R03 통합 테스트 22개를 추가했다. 실행 JAR 빌드도 성공했다.

JSON의 `latest_suite_timestamp`는 해당 작업에서 가장 늦게 시작한 테스트 suite의 시각(UTC)이다. 전체 테스트의 완료 시각이 아니다. `bootJar` 결과는 JSON 집계 대상이 아니며 별도의 Gradle 실행 결과다.

## 재현 명령

`server`에서 실행한다. Gradle Wrapper를 사용하며 MySQL 실행 파일 경로는 설치 환경에 맞춘다.

```powershell
./gradlew.bat test bootJar
python scripts/verify_auth.py
python scripts/report_web_verification.py
```

| 명령 | 확인하는 것 |
|---|---|
| `test bootJar` | MySQL 태그를 제외한 단위 테스트와 실행 JAR 생성 |
| `verify_auth.py` | 독립 MySQL 생성 → 최종 DDL 적용 → 단위/통합 테스트 → 임시 프로세스·데이터 정리 |
| `report_web_verification.py` | 최종 DDL과 서버 사본의 바이트 일치 및 마지막 Gradle XML 집계 |

MySQL 설치 경로가 다르면 다음처럼 지정한다.

```powershell
python scripts/verify_auth.py --mysql-bin "C:/Program Files/MySQL/MySQL Server 8.0/bin"
```

통합 테스트는 임의 포트(3306 제외), 임시 DB와 synthetic 자격 증명을 사용한다. test 프로필은 로컬 `.env`를 읽지 않는다. `integrationTest`만 직접 실행하면 격리 DB 증명이 없어 실패할 수 있으므로 helper를 사용한다.

집계 스크립트는 테스트를 실행하지 않으며 현재 소스와 XML의 최신성도 보장하지 않는다. 새 결과를 기록하려면 테스트 명령 성공을 먼저 확인하고 JSON과 이 문서의 수량을 함께 갱신한다.

## 검증 범위

| 영역 | 주요 검사 |
|---|---|
| 인증·보호 | 동의 선행, OAuth state 일회성, 교환 후 재검증, CSRF/Origin, 동일 계정 REAUTH와 시간 경계 |
| 사용자·홈 | DTO 검증, 소유권·version·멱등 범위, 연락망 제한, 동의별 기능 차단, 카탈로그 |
| 메시지 M01 | SAFETY/TEST 단계, 최신 수신자·본인 번호 마스킹, 동의·삭제·통화 차단, no-store·만료, 복호화 중 세션 만료·만료 상태 커밋, 본문을 읽기 전 거절, 회원 분리, 계정 잠금 대기 후 일관 읽기, 지도 설정·금지 경로 |
| 이력·삭제 R01~R03 | 회원별 종료·실패 이력과 keyset·커서 서명, 확인·재인증·멱등·접수증 소유권과 만료, 동시 접수, cutoff 이후 통화 보존, 로컬 롤백과 원래 회원 상태 복구, 키 폐기·외부 연결 해제 재시도와 선점, 커밋 결과 불명 처리 |
| 통화 | 동시 생성·재개, 페이지 키, grant 세대, 토큰 폐기, 재개 예산, lease·전체 시간 상한 |
| 삭제·보존 | 정리와 상태의 원자 커밋, 데이터·키 롤백, 미완료 가입·세션·통화 보존 경계 |
| 키 수명 | 확정 롤백에만 새 키 폐기, 커밋 결과 불명 시 보존, OAuth 키 읽기 실패 정리 |
| 계약 | 실제 MySQL 19테이블·189컬럼·171제약, HMAC 고정 벡터, HTTP client 모의 응답·오류, OpenAPI |

HTML 결과는 실행 후 `build/reports/tests/test/index.html` 및 `build/reports/tests/integrationTest/index.html`, XML은 `build/test-results`에 생성된다. build 디렉터리는 생성물이므로 저장소에 포함하지 않는다.

## 별도 확인이 필요한 것

카카오/Gemini는 자동 테스트에서 모의 처리한다. 실제 앱의 동의 scope·prompt 동작, 보안 쿠키의 브라우저 수용, 음성 입출력, WSS·GoAway 재개, 프록시 및 운영 키 저장소는 실제 환경 검수가 필요하다. M01 지도 테스트의 URL·검수 참조도 합성값이며 실제 Naver 지도 동작을 보증하지 않는다. 작성 화면·브라우저 위치 취득·1초 표시 목표는 별도 검수한다. 자세한 시나리오는 [수동 검증](swagger-test-guide.md), [메시지 검증](safety-message-swagger-test.md), [배포 조건](aws-deployment-readiness.md)을 따른다.

# 로컬 DB 설정 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [환경 설정](environment-setup.md)

[schema-mysql.sql](../db/schema-mysql.sql)은 최종 설계 DDL의 동일 사본이며 **새 빈 DB 설치용**이다. MySQL 8.0.41 이상 8.0/8.4를 사용한다. 스크립트 자체에 CREATE DATABASE safecall이 있으므로 먼저 같은 DB를 만들지 않는다.

## 신규 설치

1. Workbench 또는 mysql 클라이언트에서 로컬 관리 계정으로 접속한다.
2. safecall DB가 없는 상태를 확인한 후 전체 schema-mysql.sql을 한 번 실행한다. UTF-8, UTC, STRICT SQL mode 설정도 스크립트에 포함되어 있다.
3. safecall_app 같은 전용 계정에 해당 DB의 SELECT/INSERT/UPDATE/DELETE 권한을 부여한다. 사용자 생성과 비밀번호는 각자 환경에서 관리한다. 앱 시작 시 DDL을 자동 실행하지 않는다.
4. [환경 설정](environment-setup.md)에 따라 DB 접속 및 암호화 키를 로컬 `.env`에 설정한다. 빈 양식 파일의 값을 그대로 둔 상태로 실행하지 않는다.
5. 최초 서비스 문서를 별도 적용하고 검수한 프롬프트를 [발행 절차](ai-safety-call-reference-review.md)에 따라 준비한다. 공개 앱용 문서 발행 API는 없으며 동의 문서 3종의 최초 버전은 프로젝트 기간 동안 고정한다.

## 설치 확인

DB 관리 도구에서 다음 읽기 전용 SQL로 대상을 확인한다. 실제 DB 이름을 바꿨다면 WHERE 값도 해당 이름으로 바꾼다.

```sql
SELECT COUNT(*) AS tableCount
FROM information_schema.tables
WHERE table_schema = 'safecall';
```

최종 DDL의 테이블 수는 20이다. 서비스 문서와 프롬프트가 미발행이면 일부 기능은 DOCUMENT_NOT_READY/PROMPT_NOT_READY 등으로 제한되므로 테이블 생성 성공과 기능 준비를 구분한다.

## 기존 DB가 있을 때

최신 재검토 수정에는 [발급 시작 시각 마이그레이션](../db/migrations/20260914-grant-issuing-start.sql)도 필요하다. 아래 두 마이그레이션에 이어 앱 교체 전에 한 번 적용한다. 기존 ISSUING 행의 시작 시각은 추정하지 않으며, 시작 시각이 없는 ISSUING은 UNKNOWN으로 종료한다. 기존 PENDING은 새 워커가 발급을 시작할 때 시각을 기록한다.

2026-09-14 재검토 보완 버전에는 위 키 폐기 테이블에 더해 [통화 재개 검증 정보 마이그레이션](../db/migrations/20260914-call-prompt-anchor.sql)을 한 번 적용한다. 최초 발급 시각과 설정 HMAC 컬럼·제약만 추가한다. 기존 통화에 임의 검증 정보를 채우지 않으며, 검증 정보 없는 통화의 재개는 종료 처리한다. 새로 시작한 통화부터 정상 검증된다.

2026-09-13 검토 보완 버전으로 업데이트할 때는 **앱 업데이트 전에** 대상 DB를 선택하고 [키 폐기 작업 마이그레이션](../db/migrations/20260913-key-discard-job.sql)을 적용한다. `keyDiscardJob` 테이블만 추가하며 기존 사용자/통화 데이터는 변경하지 않는다. 이 테이블이 없으면 키 정리가 필요한 요청은 롤백된다. 신규 설치용 전체 DDL을 기존 DB에서 실행하지 않는다.

DDL은 ALTER/데이터 이관 스크립트가 아니며 CREATE DATABASE 실패에서 중단해야 한다. `--force`로 계속 실행하거나 기존 DB를 자동 삭제하지 않는다. 기존 데이터 유지 여부, 구버전 세션 폐기, 암호화 키 매핑을 결정한 별도 이관 작업이 필요하다. 이번 코드 변경은 서비스 DB에 적용하지 않았다.

## 자동 검증

서비스 DB를 사용하지 않는 임시 MySQL 실행 명령과 결과는 [자동 검증 문서](verification.md)를 따른다. Linux 환경에 적용할 때는 실제 `lower_case_table_names` 설정과 테이블 이름 대소문자를 [배포 조건](aws-deployment-readiness.md)에 따라 확인한다.

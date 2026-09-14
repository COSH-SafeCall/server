# 로컬 DB 설정 · v4.2-web-mvp

[문서 목록](README.md) · 준비: [환경 설정](setup.md)

[schema-mysql.sql](../db/schema-mysql.sql)은 최종 설계 DDL의 동일 사본이며 **새 빈 DB 설치용**이다. MySQL 8.0.41 이상 8.0/8.4를 사용한다. 스크립트 자체에 CREATE DATABASE safecall이 있으므로 먼저 같은 DB를 만들지 않는다.

## 신규 설치

1. Workbench 또는 mysql 클라이언트에서 로컬 관리 계정으로 접속한다.
2. safecall DB가 없는 상태를 확인한 후 전체 schema-mysql.sql을 한 번 실행한다. UTF-8, UTC, STRICT SQL mode 설정도 스크립트에 포함되어 있다.
3. safecall_app 같은 전용 계정에 해당 DB의 SELECT/INSERT/UPDATE/DELETE 권한을 부여한다. 사용자 생성과 비밀번호는 각자 환경에서 관리한다. 앱 시작 시 DDL을 자동 실행하지 않는다.
4. [환경 설정](setup.md)에 따라 DB 접속 및 암호화 키를 로컬 `.env`에 설정한다. 빈 양식 파일의 값을 그대로 둔 상태로 실행하지 않는다.
5. 검수한 프롬프트를 [발행 절차](setup.md#프롬프트-발행)에 따라 준비한다. 동의·안내 문구는 프론트엔드 정적 콘텐츠이며 DB에 발행하지 않는다.

## 설치 확인

DB 관리 도구에서 다음 읽기 전용 SQL로 대상을 확인한다. 실제 DB 이름을 바꿨다면 WHERE 값도 해당 이름으로 바꾼다.

```sql
SELECT COUNT(*) AS tableCount
FROM information_schema.tables
WHERE table_schema = 'safecall';
```

최종 DDL의 테이블 수는 18이다. 프롬프트가 미발행이면 통화 기능은 PROMPT_NOT_READY로 제한되므로 테이블 생성 성공과 기능 준비를 구분한다.

## 기존 DB가 있을 때

**앱 업데이트 전에** 대상 DB를 선택하고 아직 적용하지 않은 마이그레이션을 아래 순서로 한 번씩 적용한다.

1. [키 폐기 작업](../db/migrations/20260913-key-discard-job.sql): `keyDiscardJob` 추가.
2. [통화 재개 검증 정보](../db/migrations/20260914-call-prompt-anchor.sql): 최초 지침 기준 시각과 설정 HMAC 추가.
3. [발급 시작 시각](../db/migrations/20260914-grant-issuing-start.sql): `issuingStartedAt` 추가.
4. [계정 외부 정리 재시도](../db/migrations/20260914-account-external-retry.sql): `externalNextAttemptAt`과 대상 조회 인덱스 추가.
5. [정적 서비스 문서 전환](../db/migrations/20260915-static-service-documents.sql): 문서 외래 키와 `serviceDocument`를 제거하고 동의 버전을 1로 고정.
6. [20260915-client-onboarding.sql](../db/migrations/20260915-client-onboarding.sql): OAuth 동의 스냅샷·서버 화면 단계 제거. [배포 순서와 연동 계약](frontend-onboarding.md#기존-db-적용)을 따른다.

기존 통화에 임의 HMAC·발급 시작 시각을 채우지 않는다. 검증 정보 없는 통화는 재개 시 종료되며, 시작 시각 없는 ISSUING은 UNKNOWN으로 종료된다. 진행 중 통화가 끝난 뒤 앱을 교체한다. 기존 ACCOUNT 작업의 암호화된 선점 기한은 새 워커가 확인하여 유지한다. `externalNextAttemptAt`은 내부 재시도 시각이며 API의 완료 목표 기한 `dueAt`을 변경하지 않는다.

DDL은 ALTER/데이터 이관 스크립트가 아니며 CREATE DATABASE 실패에서 중단해야 한다. `--force`로 계속 실행하거나 기존 DB를 자동 삭제하지 않는다. 20260913~20260914의 앞선 네 마이그레이션보다 오래된 중간 버전은 세션·멱등 기록·암호화 키 매핑을 포함한 별도 이관 검토가 필요하다. 과거 버전에서 이미 DB 참조를 잃은 외부 키는 이 마이그레이션만으로 복구되지 않으므로 별도 대조한다.

## 자동 검증

서비스 DB를 사용하지 않는 임시 MySQL 실행 명령과 결과는 [자동 검증 문서](verification.md)를 따른다. Linux 환경에 적용할 때는 실제 `lower_case_table_names` 설정과 테이블 이름 대소문자를 [배포 조건](deployment.md)에 따라 확인한다.

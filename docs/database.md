# JPA 데이터베이스 설정

[문서 목록](README.md) · 준비: [환경 설정](setup.md)

SafeCall은 MySQL 8.0.41 이상 또는 8.4와 Spring Data JPA를 사용한다. 실제 데이터 접근 로직은 기존 `JdbcTemplate`을 유지하고, 테이블 구조 생성과 추가 컬럼 반영은 도메인별 JPA Entity를 기준으로 수행한다.

## 데이터베이스 준비

애플리케이션 실행 전에 `DB_NAME`으로 지정한 빈 데이터베이스와 접속 계정을 준비한다. JPA는 데이터베이스 자체를 생성하지 않는다.

```sql
CREATE DATABASE safecall CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
```

애플리케이션 계정에는 해당 데이터베이스의 테이블 생성·변경 및 서비스 데이터 접근 권한이 필요하다.

## 자동 스키마 갱신

공통 설정의 `spring.jpa.hibernate.ddl-auto=update`가 모든 실행 프로필에 적용된다. 서버가 시작될 때 JPA Entity와 현재 데이터베이스를 비교하여 누락된 테이블과 컬럼을 생성한다.

테이블과 컬럼의 camelCase 이름을 유지하기 위해 Hibernate 물리 네이밍 전략은 `PhysicalNamingStrategyStandardImpl`로 고정한다. UUID는 기존 JDBC 코드와 동일한 `BINARY(16)`으로 저장한다.

## Entity 패키지

| 도메인 | 패키지 | 테이블 |
|---|---|---|
| 인증 | `auth.entity` | `webSession`, `oauthAttempt`, `apiIdempotency`, `rateBucket` |
| 사용자 | `user.entity` | `appUser`, `userSetting`, `consentEvent`, `emergencyContact` |
| 통화 | `call.entity` | `scenario`, `counterpart`, `promptRelease`, `personaPrompt`, `callSession`, `callEvent`, `connectionGrant` |
| 텔레메트리 | `telemetry.entity` | `operationEvent` |
| 이력·삭제 | `history.entity` | `deletionJob` |
| 암호화 키 정리 | `common.crypto.entity` | `keyDiscardJob` |

상황 4개와 통화 상대 3개는 `CallReferenceDataInitializer`가 서버 시작 시 중복 없이 등록한다. 검수된 Gemini 프롬프트 발행은 별도의 배포 절차로 수행한다.

## 운영 확인

서버 시작 로그에서 Hibernate 스키마 갱신 실패가 없는지 확인한 뒤 다음 쿼리로 18개 테이블을 확인한다.

```sql
SELECT COUNT(*) AS tableCount
FROM information_schema.tables
WHERE table_schema = 'safecall';
```

`ddl-auto=update`는 기존 컬럼이나 테이블을 자동 삭제하지 않는다. 운영 데이터 구조를 제거하거나 데이터 변환이 필요한 변경은 별도 검토 후 수행한다.

기존 온보딩 스키마에서 현재 구조로 처음 전환할 때는 `LegacySchemaCleanup`이 구형 온보딩 테이블·컬럼을 확인하여 한 번 정리한다. 대상이 없으면 변경하지 않으며, 새 서버가 준비 상태가 되기 전에 실행된다.

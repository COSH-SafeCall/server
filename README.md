# SafeCall 서버

Java 21 · Spring Boot 4.1 · MySQL 8.0.41 이상. API 설계 v2.1의 **0장 공통 설계, 1장 A01~A07, 2장 U01~U12, 3장 H01~H02**를 구현한다. 현재 운영 배포나 실제 카카오 로그인 검증까지 완료한 상태는 아니다.

## 코드 구조

```text
src/main/java/com/safecall/service/
├─ common/config       # UTC Clock, 엄격한 JSON, 요청 ID, 요청 제한 필터
├─ common/error        # ErrorCode, CustomException, 전역/필터/기본 오류 응답
├─ common/crypto       # 용도별 HMAC, AES-GCM, DB 외부 개인별 키 저장
├─ auth/
   ├─ api              # Controller와 요청/응답 DTO
   ├─ kakao            # 카카오 검증 인터페이스와 실제 HTTP 구현
   ├─ repository       # MySQL SQL, 행 매핑, 잠금과 저장
   └─ service          # 발급·회전·로그아웃·온보딩·만료/재생 암호문 정리
├─ user/
   ├─ api              # 프로필·동의·연락망·설정 Controller와 DTO
   ├─ repository       # 사용자 행 매핑, 문서·동의·연락망 SQL
   └─ service          # 입력 정규화, 동의 철회, AI/위치 데이터 정리
└─ home/
   ├─ api              # 홈 자격·고정 통화 선택지 Controller와 DTO
   ├─ repository       # 보호자 수, 상황·상대 카탈로그 SQL
   └─ service          # 메시지 작성 조건·현재 동의 판정, 카탈로그 검증
```

`AuthService`는 외부 카카오 호출과 제한된 DB 경쟁 재시도를 맡는다. `AuthTransactions`는 DB 변경을 하나의 트랜잭션으로 묶는다. refresh 재사용·만료로 폐기한 세션은 401을 반환하더라도 폐기를 커밋하고, 로그아웃 DB 실패는 전체 롤백한다. JDBC의 UUID는 swap 없는 BINARY(16), 날짜/시간은 UTC이며 SQL 식별자는 camelCase를 보존한다.

## 구현한 API

| API | 경로 | 주요 처리 |
|---|---|---|
| A01 | POST /api/v1/auth/guest | 회원 없는 게스트 발급, bootstrap 증명, 60초 응답 재생 |
| A02 | POST /api/v1/auth/kakao | 앱 번호·subject·만료 검증, 암호화 프로필, 기존 동의 철회 보존 |
| A03 | POST /api/v1/auth/refresh | 회전, 30초 재생, 다른 키로 재사용 시 세션·통화 폐기 |
| A04 | POST /api/v1/auth/logout | 현재 회원 세션·통화·grant 종료, 같은 증명/키로 60초 결과 재조회 |
| A05 | GET /api/v1/session | 현재 credential 검증, 회원/게스트·단계·재동의 상태 |
| A06 | GET /api/v1/onboarding | 필수 정보·현재 문서 동의·진행 조건 |
| A07 | POST /api/v1/onboarding/advance | 단계·버전 검증, 권한 안내 관측, 선택 테스트·완료 |
| U01 | GET /api/v1/me/profile | 회원 프로필 조회, 카카오/사용자 확인 출처 표시 |
| U02 | PUT /api/v1/me/profile | 이름·전화번호·성별·생년월일 정규화, 버전 충돌·연락망 중복 방지 |
| U03 | GET /api/v1/documents | 공개 문서 조회, codes 필터, ETag/304 |
| U04 | GET /api/v1/me/consents | 현재 동의 상태와 문서 버전 유효성 조회 |
| U05 | POST /api/v1/me/consents | 1~3개 동의 결정 일괄 기록, 멱등 재시도 |
| U06 | POST /api/v1/me/consents/{code}/withdraw | 동의 철회, 데이터 정리 작업 등록, 영수증 60초 재조회 |
| U07 | GET /api/v1/me/emergency-contacts | 보호자 연락망 목록 조회 |
| U08 | POST /api/v1/me/emergency-contacts | 보호자 연락망 생성, 최대 2명·번호 중복 방지 |
| U09 | PUT /api/v1/me/emergency-contacts/{contactId} | 보호자 연락망 수정, 소유권·버전 검증 |
| U10 | DELETE /api/v1/me/emergency-contacts/{contactId} | 보호자 연락망 삭제, 삭제 요청 멱등 처리 |
| U11 | GET /api/v1/me/settings | 사용자 알림 설정 조회 |
| U12 | PATCH /api/v1/me/settings | 알림 모드 수정, 버전 충돌 검증 |
| H01 | GET /api/v1/home | 게스트·회원 메시지 작성 자격, 차단 사유, 위치 동의, 보호자 수 |
| H02 | GET /api/v1/call-options | 상황 4개·상대 3개, 빠른 시작 기본값, 인증 후 ETag/304 |

H01은 현재 개인정보 처리 동의와 프로필 확인·온보딩 완료·보호자 수를 검사하며 AI 동의를 메시지 조건으로 요구하지 않는다. 위치 동의는 Android 권한·실제 위치 취득 결과와 별개다. H02는 기존 DB 고정 카탈로그를 사용하며 프롬프트 발행 상태에 의존하지 않는다. catalogVersion=2는 배포 코드의 고정 버전이며 카탈로그 변경 배포 시 함께 관리한다. ETag는 버전뿐 아니라 전체 응답 내용으로 계산한다. H02는 private/no-cache 재검증을 사용하고 304 처리 전에 세션을 확인한다.

A07 재시도는 같은 작업인지 먼저 검사하고 현재 온보딩 상태를 반환한다. 게스트 수명은 최초 발급 후 총 24시간이며 refresh로 늘어나지 않는다. 권한 거부 자체는 진행을 막지 않으며 게스트 위치 권한은 받지 않는다. 문자 발송/SENS 구현은 없다.

오류는 `timestamp/status/code/message/errors/path` 6필드다. 오류 시각은 UTC, 소수점 6자리이며 검증 실패의 `value`는 항상 null이다. JSON 알 수 없는 필드·중복 키·숫자 enum·잘못된 타입을 거절한다. 개인정보 응답에는 `no-store`, 모든 요청에는 서버 생성 `X-Request-Id`를 사용한다. 토큰·프로필·공급자 본문·SQL 예외 원문은 로그에 남기지 않는다.

## 로컬 실행

1. Java 21과 MySQL을 준비한다.
2. `db/schema-mysql.sql`을 **새 빈 개발 DB**에 별도로 적용한다. 설계 v2.1 DDL의 동일 사본이며 기존 DB 마이그레이션이 아니다. 애플리케이션은 DDL을 자동 실행하지 않는다.
3. `.env.example`을 `.env`로 복사하고 DB 전용 계정·비밀번호, 독립된 세 키, 카카오 앱 번호를 입력한다.
4. 서버 폴더에서 `./gradlew.bat bootRun`을 실행한다. 기본 포트는 8081이다.

프로필 미지정 시 `local`로 실행하며, 공통 `application.yml`에 `application-local.yml`을 더해 `.env`를 읽는다. JVM/OS 환경 변수로도 주입할 수 있다. `JWT_SECRET`은 32바이트 이상의 난수 기반 문자열, `HMAC_SECRET`과 `RESPONSE_ENCRYPTION_SECRET`은 각각 독립된 난수 32바이트의 Base64다. 빈 값·짧은 키·키 재사용은 시작 시 거절한다. 예제 파일에는 동작 가능한 비밀 값을 넣지 않았다.

AWS 실행 환경에는 `SPRING_PROFILES_ACTIVE=prod`를 명시한다. `application-prod.yml`은 `.env`를 자동으로 읽지 않고 Swagger를 비활성화하며 MySQL TLS 호스트 검증을 요구한다. 로컬 파일 키 저장소도 선택하지 않는다. **현재는 외부 사용자 키 저장소가 미구현이므로 운영 프로필 시작을 차단한다.** 배포 전 남은 작업과 환경별 차이는 [AWS 배포 준비 문서](docs/aws-deployment-readiness.md)를 따른다.

`KAKAO_APP_ID`는 숫자 앱 번호다. `KAKAO_CLIENT_ID`인 REST API 키와 다르다. A02는 앱 SDK가 받은 access token을 검증하므로 서버 authorization-code 교환이나 callback을 구현하지 않는다. 앱 번호를 0으로 설정하면 A02는 `KAKAO_UNAVAILABLE`로 닫히며 게스트 개발은 가능하다.

`CRYPTO_KEY_DIRECTORY`는 DB와 분리한 개인별 AES 키 파일 디렉터리다. 로컬 구현은 `FileUserKeyStore`를 사용하고 각 회원에 다른 난수 키를 만든다. 계정 생성 롤백 시 새 키도 제거한다. 폴더 ACL은 서비스 계정만 접근하도록 설정하고, 다중 서버 운영에서는 공용 KMS/HSM 구현으로 `UserKeyStore`를 교체해야 한다. `.env`와 `.keys/`는 Git에서 제외한다. 다른 키 경로를 지정했다면 그 경로도 소스 관리에서 제외한다.

HTTP는 로컬 개발용이다. 실제 서비스에서는 신뢰할 인입 계층에서 HTTPS를 종료하고 서버 직접 접근을 제한해야 한다. 현재 서버는 전달 헤더를 신뢰하지 않고 직접 연결 IP에 인증 시도 분당 10회 제한을 적용한다. 프록시를 추가할 때 실제 클라이언트 IP 검증과 해당 제한을 함께 구성한다.

## 검증

로컬 DB와 `.env` 준비는 [로컬 MySQL 설정 가이드](docs/local-db-setup.md)를 따른다. A01~A07 및 U01~U12 수동 API 테스트는 [로그인·사용자 API Swagger 통합 검수 가이드](docs/swagger-test-guide.md)를 따른다. U03/U04/U05/U06 테스트용 개발 문서는 [개발용 문서 시드](db/dev/seed-test-documents.sql)를 빈 로컬 개발 DB에만 적용한다. 서버 실행 후 [Swagger UI](http://localhost:8081/swagger-ui/index.html)에서 A01~A07과 U01~U12를 실행할 수 있다. OpenAPI JSON은 `/v3/api-docs`로 제공한다. 설정은 [springdoc 공식 문서](https://springdoc.org/getting-started.html)의 Spring Boot 4용 3.1.1을 사용한다.

2026-09-10 검증: 단위·설정 테스트 17개와 MySQL HTTP 통합 테스트 53개, 총 **70개 통과**, bootJar 성공. 기존 인증·사용자 검증에 홈 자격/문서 버전 변경/위치 동의 철회, 카탈로그 내용·ETag 변경, 조건부 조회의 인증 검증, 만료·로그아웃·계정 삭제 대기 차단과 OpenAPI 검증을 추가했다. 통합 테스트는 일회용 MySQL을 사용하며 서비스 DB를 변경하지 않는다.

외부 서비스나 MySQL 없이 실행하는 테스트:

```powershell
./gradlew.bat test
```

일회용 MySQL을 직접 초기화하고 실제 HTTP·트랜잭션·경쟁 조건까지 검증:

```powershell
python scripts/verify_auth.py
```

MySQL 경로가 다르면 `--mysql-bin "설치경로/bin"`을 전달한다. 이 스크립트는 임의의 로컬 포트와 `safecall_auth_test_<난수>` DB를 만들며 `.env`의 서비스 DB를 사용하지 않는다. 종료 시 mysqld 프로세스가 끝난 것을 확인한 뒤 테스트 데이터/키 폴더를 제거한다. 비밀번호 없는 root는 이 임시 인스턴스 안에서만 사용한다. 결과는 `build/reports/tests/test/`와 `build/reports/tests/integrationTest/`에 남는다. 테스트는 실제 카카오 계정 대신 합성 HTTP 응답/검증 대역을 사용한다.

## 다음 구현과 연결할 부분

- 미완료 가입의 24시간 개인정보 정리, 계정 삭제·외부 카카오 연결 해제·키 폐기 작업은 6장 삭제 처리와 함께 구현해야 한다. 현재 스케줄러는 세션 만료, 인증 응답 암호문/인증 멱등/요청 제한 데이터, AI/위치 동의 철회 작업을 정리한다. ACCOUNT 철회 영수증 발급과 계정의 `DELETION_PENDING` 전환은 포함했지만 최종 계정 삭제 worker는 아직 없다.
- 운영 카카오 키/권한 항목을 사용한 실연동, Android 보호 저장소·single-flight refresh·권한 화면 연동은 별도 검증이 필요하다.
- Gemini 통화 생성, 메시지 작성 API는 이후 장의 범위다. 다만 A04와 토큰 재사용 시 기존 통화/grant를 종료하는 DB 처리, U06 동의 철회 시 활성 통화 종료 처리는 포함했다.

카카오 검증 필드는 [공식 REST API 문서](https://developers.kakao.com/docs/ko/kakaologin/rest-api)의 access token 정보 조회와 사용자 정보 조회를 기준으로 한다.

# 로컬 MySQL 설정 가이드

이 문서는 로컬 PC에서 SafeCall 서버를 실행하기 위한 MySQL Workbench, DB 계정, `.env` 설정 절차다. 실제 비밀번호와 개인 키는 문서나 Git에 남기지 않는다.

## 1. MySQL Workbench 접속

처음에는 MySQL 설치 때 만든 `root` 계정으로 접속한다.

```text
Connection Method: Standard TCP/IP
Hostname: 127.0.0.1
Port: 3306
Username: root
Password: 설치할 때 정한 root 비밀번호
```

`Default Schema`에 `safecall`을 넣었는데 `Unknown database 'safecall'`이 나오면, 아직 DB가 만들어지지 않은 상태다. `Default Schema`를 비우고 다시 접속한다.

`Access denied for user 'root'@'localhost' (using password: NO)`가 나오면 Workbench가 비밀번호를 보내지 않은 것이다. 연결 설정에서 `Store in Vault...`를 눌러 root 비밀번호를 저장한 뒤 다시 테스트한다.

## 2. safecall DB 만들기

`safecall` DB가 없을 때만 실행한다.

```sql
CREATE DATABASE IF NOT EXISTS safecall
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_as_cs;
```

`Can't create database 'safecall'; database exists`가 나오면 이미 만들어진 상태다. 오류처럼 보이지만 다음 단계로 넘어가면 된다.

## 3. 서버 전용 DB 계정 만들기

애플리케이션은 `root`가 아니라 `safecall_app` 계정으로 접속한다. 비밀번호는 각자 로컬에서 정한다.

```sql
CREATE USER IF NOT EXISTS 'safecall_app'@'127.0.0.1'
  IDENTIFIED BY '여기에_쓸_비밀번호';

CREATE USER IF NOT EXISTS 'safecall_app'@'localhost'
  IDENTIFIED BY '여기에_쓸_비밀번호';

GRANT SELECT, INSERT, UPDATE, DELETE ON safecall.* TO 'safecall_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON safecall.* TO 'safecall_app'@'localhost';

FLUSH PRIVILEGES;
```

그 다음 Workbench에서 새 연결을 만들고 `safecall_app`으로 접속되는지 확인한다.

```text
Connection Name: SafeCall Local
Connection Method: Standard TCP/IP
Hostname: 127.0.0.1
Port: 3306
Username: safecall_app
Password: 위에서 정한 비밀번호
Default Schema: safecall
```

## 4. 테이블 생성

Workbench에서 `server/db/schema-mysql.sql`을 열고 전체 실행한다. 이 파일은 현재 설계 DDL의 MySQL 사본이며 애플리케이션이 자동 실행하지 않는다.

실행 후 아래 명령으로 테이블이 만들어졌는지 확인한다.

```sql
USE safecall;
SHOW TABLES;
```

## 5. .env 작성

`server/.env.example`을 참고해서 `server/.env`를 만든다. `.env`는 Git에 올리지 않는다.

```env
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=safecall
DB_USERNAME=safecall_app
DB_PASSWORD=방금_정한_safecall_app_비밀번호
SERVER_PORT=8081
SWAGGER_ENABLED=true

JWT_SECRET=아래_명령으로_생성
JWT_ISSUER=safecall
JWT_AUDIENCE=safecall-android
JWT_ACCESS_EXPIRATION=900000
JWT_REFRESH_EXPIRATION=1209600000

HMAC_SECRET=아래_명령으로_생성
RESPONSE_ENCRYPTION_SECRET=아래_명령으로_생성
CRYPTO_KEY_DIRECTORY=.keys

KAKAO_APP_ID=숫자_앱_ID
KAKAO_CLIENT_ID=REST_API_KEY
KAKAO_CLIENT_SECRET=
KAKAO_REDIRECT_URI=http://localhost:8081/login/oauth2/code/kakao
AUTH_REQUESTS_PER_MINUTE=10
AUTH_CLEANUP_DELAY_MS=1000
```

`JWT_SECRET`, `HMAC_SECRET`, `RESPONSE_ENCRYPTION_SECRET`은 서로 다른 랜덤값이어야 한다. 구버전 PowerShell에서도 동작하는 명령이다.

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

foreach ($name in "JWT_SECRET","HMAC_SECRET","RESPONSE_ENCRYPTION_SECRET") {
  $bytes = New-Object byte[] 32
  $rng.GetBytes($bytes)
  "$name=$([Convert]::ToBase64String($bytes))"
}

$rng.Dispose()
```

`AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`처럼 반복되는 값이 나오면 실패한 값이다. 다시 생성한다.

## 6. 서버 실행 확인

서버 폴더에서 실행한다. 프로필을 지정하지 않으면 `local`로 시작하며 `application-local.yml`이 `.env`를 읽는다. 기존 `.env`를 변경할 필요는 없다.

```powershell
cd C:\Users\tisxo\AI_Championship\server
.\gradlew.bat bootRun
```

정상 실행되면 Tomcat이 `8081` 포트로 시작된다. `Crypto secrets must contain 32 random bytes encoded as Base64.`가 나오면 `HMAC_SECRET` 또는 `RESPONSE_ENCRYPTION_SECRET` 형식이 잘못된 것이다. 위 PowerShell 명령으로 다시 생성한다.

서버가 켜진 뒤 Swagger 테스트는 `docs/auth-session-onboarding-swagger-test.md`를 따른다.

# SafeCall 서버

Java 21 · Spring Boot 4.1 · MySQL 8.0.41 이상. 웹 MVP API 1~7장, 총 33개 HTTP 작업을 구현한다. 인증은 HttpOnly 세션 쿠키와 CSRF를 사용한다.

| 시작점 | 문서 |
|---|---|
| 환경·외부 서비스 설정 | [환경 설정](docs/setup.md) |
| 신규 DB·기존 DB 업데이트 | [DB 설치·마이그레이션](docs/database.md) |
| 코드 구조·정책·전체 API | [서비스 구조](docs/architecture.md) |
| API 수동 테스트 | [Swagger 흐름](docs/swagger/README.md) |
| 자동 테스트·실행 결과 | [검증](docs/verification.md) |
| 운영 전환 | [배포 조건](docs/deployment.md) |

설정과 DB 준비 후 `server` 폴더에서 실행한다.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat bootRun
```

Swagger는 `/swagger-ui/index.html`, OpenAPI는 `/v3/api-docs`다. 기존 DB는 앱 교체 전에 미적용 마이그레이션을 적용한다. `.env.example`은 빈 양식이며 실제 설정값·키를 저장소에 올리지 않는다.

최신 테스트 수와 실행 시각은 [검증 문서](docs/verification.md)에서 관리한다. 실제 카카오·Gemini·브라우저 검수와 운영 외부 키 저장소 구현은 별도이며, 외부 UserKeyStore가 없으면 prod 시작을 차단한다.

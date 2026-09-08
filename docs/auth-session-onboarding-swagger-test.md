# 인증·세션·온보딩 Swagger 검수 가이드

이 문서는 Swagger UI로 A01~A07을 직접 확인하는 순서다. Swagger의 `Execute`는 실제 로컬 DB에 데이터를 넣고 토큰을 발급한다. 운영 키나 개인 토큰을 문서, Git, 채팅에 남기지 않는다.

## 1. 사전 준비

MySQL DB, `safecall_app` 계정, `.env` 설정은 먼저 `docs/local-db-setup.md`를 따라 준비한다. DB 설정이 끝난 뒤 이 문서의 Swagger 테스트를 진행한다.

## 2. 서버와 Swagger 열기

서버 폴더에서 실행한다.

```powershell
cd C:\Users\tisxo\AI_Championship\server
.\gradlew.bat bootRun
```

서버가 켜지면 브라우저에서 연다.

- Swagger UI: http://localhost:8081/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8081/v3/api-docs

`SERVER_PORT`를 바꿨다면 주소의 포트도 같이 바꾼다. `SWAGGER_ENABLED=false`이면 Swagger UI와 OpenAPI JSON이 꺼진다.

## 3. 테스트 값 만들기

새 요청마다 `Idempotency-Key`에 새 UUID를 넣는다. 같은 요청을 네트워크 문제로 다시 보내는 경우에만 같은 키와 같은 본문을 유지한다.

```powershell
[guid]::NewGuid().ToString()
```

A01의 `bootstrapSecret`은 아래처럼 만든다.

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$rng.Dispose()
```

`installationId`도 UUID다. `Idempotency-Key`와 같은 값을 써도 형식상 실행은 되지만, 의미가 다르므로 따로 만드는 편이 낫다.

## 4. 게스트 플로우

1. A01 `POST /api/v1/auth/guest`를 실행한다. 최초 호출은 Authorize 없이 가능하다. 성공하면 `201`과 `tokens`가 나온다.
2. 응답의 `tokens.accessToken`을 복사한다.
3. Swagger 오른쪽 위 `Authorize`를 열고 `accessToken`에 JWT만 넣는다. `Bearer`는 붙이지 않는다.
4. A05 `GET /api/v1/session`을 실행한다. `kind=GUEST`, `onboardingStep=PERMISSIONS`를 확인한다.
5. A06 `GET /api/v1/onboarding`을 실행한다. 처음에는 `step=PERMISSIONS`, `version=1`이 정상이다.
6. A07 `POST /api/v1/onboarding/advance`를 새 `Idempotency-Key`로 실행한다.

PERMISSIONS 단계의 A07 body는 다음과 같다.

```json
{
  "step": "PERMISSIONS",
  "expectedVersion": 1,
  "permissionReview": {
    "microphone": true,
    "location": false
  }
}
```

성공하면 `step=SOS_GUIDE`, `version=2`가 나온다. 그 다음 A07을 새 `Idempotency-Key`와 아래 body로 한 번 더 실행한다.

```json
{
  "step": "SOS_GUIDE",
  "expectedVersion": 2
}
```

성공 결과가 `step=COMPLETE`, `version=3`, `isAdvanceAllowed=false`이면 게스트 온보딩은 끝이다. 마지막으로 A06을 다시 실행해서 `COMPLETE`인지 확인한다.

## 5. 토큰 갱신

A03 `POST /api/v1/auth/refresh`는 Authorize 값 대신 본문의 refresh token으로 인증한다.

```json
{
  "refreshToken": "A01_또는_A02에서_받은_refreshToken"
}
```

성공하면 새 `accessToken`과 새 `refreshToken`이 나온다. Swagger 오른쪽 위 `Authorize`에서 `Logout`을 누르면 서버 로그아웃이 아니라 Swagger에 저장된 토큰만 지워진다. 기존 토큰을 지운 뒤 새 `accessToken`으로 다시 Authorize한다.

이미 갱신에 사용한 이전 refresh token을 다른 `Idempotency-Key`로 다시 보내면 `TOKEN_REUSED`가 나고 세션이 폐기될 수 있다. 의도한 재사용 탐지 테스트가 아니면 이전 refresh token은 버린다.

## 6. 카카오 access token 받기

A02의 `kakaoAccessToken`에는 카카오 REST API 키, 앱 ID, client secret, SafeCall JWT를 넣지 않는다. 카카오 로그인으로 발급받은 실제 사용자 access token을 넣는다.

카카오 디벨로퍼스 설정:

- 카카오 로그인 활성화: ON
- Redirect URI: `http://localhost:8081/login/oauth2/code/kakao`
- `KAKAO_APP_ID`: 숫자 앱 ID
- `KAKAO_CLIENT_ID`: REST API 키
- Client Secret을 사용함으로 켰다면 `KAKAO_CLIENT_SECRET`도 준비

브라우저에서 아래 주소를 연다. `client_id`에는 REST API 키를 중괄호 없이 넣는다.

```text
https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=REST_API_KEY&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Flogin%2Foauth2%2Fcode%2Fkakao
```

로그인 후 브라우저가 아래처럼 돌아오면, 화면에 404가 떠도 괜찮다. 현재 서버에는 콜백 API가 아직 없고, Swagger 테스트에서는 주소창의 `code`만 필요하다.

```text
http://localhost:8081/login/oauth2/code/kakao?code=...
```

PowerShell에서 code를 access token으로 교환한다. Markdown 링크로 변환되는 일을 피하려고 URL을 문자열 조합으로 둔다.

```powershell
$clientId = "REST_API_KEY"
$code = "방금_주소창에서_받은_code"
$tokenUri = "https://" + "kauth.kakao.com" + "/oauth/token"
$redirectUri = "http://" + "localhost:8081" + "/login/oauth2/code/kakao"

Invoke-RestMethod -Method Post -Uri $tokenUri -ContentType "application/x-www-form-urlencoded;charset=utf-8" -Body @{
  grant_type = "authorization_code"
  client_id = $clientId
  redirect_uri = $redirectUri
  code = $code
}
```

카카오 디벨로퍼스에서 Client Secret을 사용함으로 켰다면 아래처럼 `client_secret`도 포함한다.

```powershell
$clientId = "REST_API_KEY"
$clientSecret = "KAKAO_CLIENT_SECRET"
$code = "방금_주소창에서_받은_code"
$tokenUri = "https://" + "kauth.kakao.com" + "/oauth/token"
$redirectUri = "http://" + "localhost:8081" + "/login/oauth2/code/kakao"

Invoke-RestMethod -Method Post -Uri $tokenUri -ContentType "application/x-www-form-urlencoded;charset=utf-8" -Body @{
  grant_type = "authorization_code"
  client_id = $clientId
  client_secret = $clientSecret
  redirect_uri = $redirectUri
  code = $code
}
```

성공 응답의 `access_token`만 A02의 `kakaoAccessToken`에 넣는다. `refresh_token`, `id_token`, `token_type`은 넣지 않는다.

```json
{
  "installationId": "새 UUID",
  "platform": "ANDROID",
  "appVersion": "1.0.0",
  "osVersion": "16",
  "kakaoAccessToken": "카카오_access_token"
}
```

A02가 성공하면 `kind=KAKAO`, `onboardingStep=PROFILE`, SafeCall `tokens`가 나온다. 카카오 동의항목에 이름, 성별, 생년월일, 전화번호가 없으면 profile 값이 `null` 또는 `UNKNOWN`일 수 있다. 현재 2장 프로필 확인 API가 아직 없으므로 신규 카카오 회원은 Swagger만으로 `PROFILE` 이후를 완료하지 못한다.

## 7. 로그아웃

게스트 세션으로 A04를 호출하면 `403 LOGIN_REQUIRED`가 정상이다.

카카오 A02 성공 후에는 응답의 SafeCall `accessToken`을 Swagger `Authorize`에 넣고 A04 `POST /api/v1/auth/logout`을 실행한다.

```json
{}
```

성공하면 `204 No Content`가 나온다. 그 다음 A05를 다시 호출했을 때 `401 SESSION_EXPIRED`가 나오면 로그아웃 후 세션 차단까지 확인된 것이다.

## 8. 자주 보는 결과

| 결과 | 의미와 조치 |
|---|---|
| 201 | A01 게스트 세션 발급 성공 |
| 200 | 조회, 갱신, 카카오 로그인, 온보딩 진행 성공 |
| 204 | A04 카카오 회원 로그아웃 성공 |
| 400 INVALID_REQUEST / VALIDATION_FAILED | 필수값, UUID 형식, JSON 필드 타입 확인 |
| 401 SESSION_EXPIRED | Authorize에 최신 SafeCall access token을 넣었는지 확인 |
| 401 KAKAO_TOKEN_INVALID | A02에 실제 카카오 access token을 넣었는지 확인 |
| 403 LOGIN_REQUIRED | 게스트로 회원 전용 로그아웃을 호출한 경우 정상 |
| 409 IDEMPOTENCY_CONFLICT | 같은 Idempotency-Key로 다른 본문을 보냄 |
| 409 ONBOARDING_STEP_MISMATCH | A06으로 현재 step/version을 다시 확인하고 A07 body 수정 |
| 409 TOKEN_REUSED | 이미 사용한 refresh token을 다른 요청으로 다시 보냄 |
| 429 RATE_LIMITED | `Retry-After`만큼 기다린 뒤 재시도 |
| 503 KAKAO_UNAVAILABLE | `KAKAO_APP_ID`, 외부 카카오 연결, Client Secret 설정 확인 |

## 9. 보안 메모

Swagger 테스트 중 나온 SafeCall access token, refresh token, 카카오 access token, 카카오 refresh token, id token은 문서나 Git에 남기지 않는다. 채팅이나 화면 공유에 붙여넣은 토큰은 노출된 것으로 보고 테스트 후 폐기한다. SafeCall 세션은 A04로 폐기할 수 있고, 카카오 토큰은 만료를 기다리거나 카카오 계정의 연결된 서비스에서 연결을 끊어 정리한다.

프론트엔드가 붙으면 PowerShell의 code 교환은 쓰지 않는다. 앱이나 웹 프론트가 카카오 SDK 또는 OAuth 흐름으로 카카오 access token을 받은 뒤 A02에 전달한다. Swagger의 수동 교환은 백엔드 API를 먼저 검수하기 위한 로컬 테스트 절차다.

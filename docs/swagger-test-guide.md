# 로그인부터 사용자 설정까지 Swagger 통합 검수 가이드

대상: A01~A07, U01~U12. 요청 필드는 현재 `AuthDtos`, `UserDtos`, 컨트롤러 및 기존 검수 문서를 기준으로 확인했다. DB 계정·환경 변수 설정은 [로컬 DB 설정](local-db-setup.md)에 별도로 둔다.

각 단계 제목을 클릭하면 내용을 접고 펼칠 수 있다. GitHub Markdown 미리보기에서 `<details>`를 지원하며, 일부 편집기는 원문 HTML로 표시할 수 있다.

**권장 순서:** 준비 → 새 설치 생성 → 게스트 검수 → 카카오 로그인 → 프로필 → 연락망 등록 → 문서·동의 → 회원 온보딩 → 설정 → 갱신·로그아웃 → 선택 오류/철회 검수.

성공 판정은 HTTP 상태뿐 아니라 응답 값과 재조회 결과까지 확인한다. 예제의 `version: 1`은 실제 조회 값이 1일 때만 사용한다. 이 문서 통과가 운영 보안 전체의 검증을 뜻하지는 않는다.


<details open>
<summary><strong>0. 필수: 검수 범위와 비밀값 취급</strong></summary>

- 로컬 테스트 DB와 본인 테스트 계정으로 실행한다. Swagger Execute는 실제 저장·발급·삭제를 수행한다.
- 문서·커밋·PR에는 실제 access/refresh/id token, 인가 code, client secret, JWT/HMAC/암호화 키, receiptToken을 넣지 않는다. 아래 예제에는 실제 비밀값이 없다.
- 전화번호 예제는 형식 검수용이며 미사용 번호라는 보장은 없다. 실제 SMS·전화 연동 검수에는 본인이 통제하는 번호를 사용하고, 이 문서에서는 실제 발송을 하지 않는다.
- PowerShell에서 비밀값을 명령 문자열에 직접 적으면 명령 기록에 남을 수 있다. 아래 카카오 교환은 숨김 입력을 사용하며 전체 토큰 응답을 출력하지 않는다. 실행 중 프로세스 메모리와 Swagger에는 값이 존재한다.
- Swagger Authorize의 Logout은 브라우저 토큰만 제거한다. A04는 SafeCall 세션을 폐기한다. 어느 것도 카카오 토큰까지 폐기했다는 뜻은 아니다. 이미 공유한 비밀값은 종류에 맞게 폐기·교체하고, 화면이나 기록 삭제만으로 해결됐다고 판정하지 않는다.
- 로컬 Swagger 검수 절차를 운영 서버 공개 설정으로 복사하지 않는다. 배포 시 TLS, 접근 제한, 비밀값 주입, 저장소·로그 정책은 별도 검토 대상이다.

</details>

<details>
<summary><strong>1. 필수: 서버 시작과 입력값 규칙</strong></summary>

MySQL DB, `safecall_app` 계정, `.env` 설정은 먼저 `docs/local-db-setup.md`를 따라 준비한다. DB 설정이 끝난 뒤 이 문서의 Swagger 테스트를 진행한다.

서버 폴더에서 실행한다.

```powershell
cd C:\Users\tisxo\AI_Championship\server
.\gradlew.bat bootRun
```

서버가 켜지면 브라우저에서 연다.

- Swagger UI: http://localhost:8081/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8081/v3/api-docs

`SERVER_PORT`를 바꿨다면 주소의 포트도 같이 바꾼다. `SWAGGER_ENABLED=false`이면 Swagger UI와 OpenAPI JSON이 꺼진다.

Swagger에서 해당 API를 펼친 뒤 Try it out → 입력 → Execute 순서로 실행한다. 헤더·query·body는 서로 다른 입력 위치다. `PS C:\...>`, `>>`, 오류 메시지, Markdown 링크 표기는 명령에 복사하지 않는다.

| 입력 | 규칙 |
| --- | --- |
| installationId | 설치 단위 UUID. 같은 설치의 게스트→카카오 전환에서 유지 |
| bootstrapSecret | 보안 난수 32바이트 이상을 Base64URL로 인코딩, 문자열 43~256자 |
| Idempotency-Key | 새 행동마다 새 UUID. 같은 요청 재시도는 같은 키·본문 유지 |
| Authorize | SafeCall access token 값만 입력. Bearer 접두사는 UI가 추가 |
| expectedVersion | 해당 자원 조회 결과의 version. 프로필·연락처·설정·온보딩 버전을 혼용하지 않음 |
| 문서 version | U03의 문서별 현재 버전. U05/U06에 사용 |

새 멱등 키는 PowerShell에서 `[guid]::NewGuid().ToString()`으로 생성한다. 인증 API에는 Swagger에 표시된 필수 헤더를 모두 입력한다.

</details>

<details>
<summary><strong>2. 필수: A01 게스트 생성 → A05/A06 → A07</strong></summary>

### 새 설치로 수동 테스트하기

Swagger 예제의 고정 `installationId`를 반복 사용하면 기존 세션과 충돌할 수 있다. 독립적인 새 테스트는 아래 PowerShell로 요청 본문을 생성하고, 출력된 JSON 전체를 A01 Request body에 붙여 넣는다.

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()

@{
  installationId = [guid]::NewGuid().ToString()
  platform = "ANDROID"
  appVersion = "1.0.0"
  osVersion = "16"
  bootstrapSecret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
} | ConvertTo-Json
```

`Idempotency-Key`는 별도로 `[guid]::NewGuid().ToString()`을 실행해 만든다. Swagger의 Authorize → Logout → Close로 저장된 인증 값을 지운 뒤 최초 요청을 보낸다. 정상 결과는 `201`, `session.kind=GUEST`, `session.onboardingStep=PERMISSIONS`다. 발급된 토큰은 문서에 복사하지 않는다.

| 오류 | 원인과 조치 |
| --- | --- |
| 400 `VALIDATION_FAILED`, `bootstrapSecret` 관련 오류 | 누락·길이·인코딩을 확인한다. 문자열은 43~256자이며 Base64URL 디코딩 결과가 32바이트 이상이어야 한다. 위 생성 명령을 사용한다. |
| 401 `AUTHENTICATION_REQUIRED` | 같은 설치에 아직 유효한 세션이 있으면 새 세션 생성에 기존 세션 소유 증명이 필요하다. 기존 설치를 이어갈 때는 현재 서버 access token을 Authorize에 넣거나 본문의 `currentRefreshToken`으로 현재 refresh token을 보낸다. 독립 테스트라면 새 설치 ID와 bootstrap secret을 생성한다. Authorize 해제만으로 기존 설치 충돌이 해결되지는 않는다. |

### 생성 이후 검수

1. 위에서 A01 `POST /api/v1/auth/guest`의 `201` 응답을 받았다면 재호출하지 않고 그 응답을 사용한다.
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
    "microphone": "GRANTED"
  }
}
```

게스트 PERMISSIONS에는 microphone만 넣고 location은 생략한다. 회원 PERMISSIONS에는 microphone과 location이 모두 필요하다. 성공하면 `step=SOS_GUIDE`, `version=2`가 나온다. 그 다음 A07을 새 `Idempotency-Key`와 아래 body로 한 번 더 실행한다.

```json
{
  "step": "SOS_GUIDE",
  "expectedVersion": 2
}
```

성공 결과가 `step=COMPLETE`, `version=3`, `isAdvanceAllowed=false`이면 게스트 온보딩은 끝이다. 마지막으로 A06을 다시 실행해서 `COMPLETE`인지 확인한다.



**판정:** A01=201, kind=GUEST. A05/A06=200. 새 게스트 기준 PERMISSIONS→SOS_GUIDE→COMPLETE, version 증가, 최종 isAdvanceAllowed=false. `profile: null`, `userId: null`은 게스트에서 정상이다. 이미 진행한 단계는 재호출하지 말고 A06 결과를 따른다.

온보딩이 완료되어도 게스트가 회원으로 바뀌지는 않는다. 다음 A02에서 사용할 현재 SafeCall access token과 installationId를 로컬에서 유지한다.

</details>

<details>
<summary><strong>3. 필수: 실제 카카오 토큰 받기 → A02 회원 로그인</strong></summary>

### 카카오 준비 및 인가 코드

테스트 앱의 카카오 로그인을 활성화하고 Redirect URI를 `http://localhost:8081/login/oauth2/code/kakao`로 등록한다. 서버의 KAKAO_APP_ID 등 환경 설정은 DB/환경 설정 문서를 따른다. REST API 키, 숫자 앱 ID, client secret은 서로 다른 값이다.

아래 주소의 REST_API_KEY를 본인 앱의 값으로 바꿔 브라우저에서 연다. 중괄호를 넣지 않는다.

```text
https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=REST_API_KEY&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Flogin%2Foauth2%2Fcode%2Fkakao
```

로그인 후 `/login/oauth2/code/kakao?code=...`에서 404가 나더라도 주소창에 code가 있으면 이 수동 검수에서는 다음 교환으로 진행한다. 현재 콜백 처리 API가 없는 경로이며 실제 서비스의 완성된 로그인 흐름은 아니다. code는 access token이 아니고 재사용하지 않는다.

### PowerShell 교환 — 코드 블록 전체 실행

Client Secret이 활성화되어 있으면 반드시 정확한 값을 입력한다. 비활성인 앱만 빈 Enter로 생략한다. code와 secret은 숨김 입력이라 입력 중 보이지 않는다. Read-Host 입력란에는 값만 붙여 넣고 Enter를 누른다.

```powershell
function Read-LocalSecret([string]$Prompt) {
  $secure = Read-Host $Prompt -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secure.Dispose()
  }
}

$clientId = Read-Host '카카오 REST API 키'
$redirectUri = 'http://localhost:8081/login/oauth2/code/kakao'
$code = Read-LocalSecret '방금 발급한 code'
$clientSecret = Read-LocalSecret 'Client Secret (비활성일 때만 빈 Enter)'
$tokenBody = @{
  grant_type = 'authorization_code'
  client_id = $clientId
  redirect_uri = $redirectUri
  code = $code
}
if ($clientSecret) { $tokenBody.client_secret = $clientSecret }
$kakaoToken = $null
try {
  $kakaoToken = Invoke-RestMethod -Method Post -Uri 'https://kauth.kakao.com/oauth/token' -ContentType 'application/x-www-form-urlencoded;charset=utf-8' -Body $tokenBody
  if ($kakaoToken.access_token) { Write-Host '카카오 토큰 발급 성공 (값은 출력하지 않음)' }
} catch {
  Write-Host '토큰 교환 실패. KOE010은 Client Secret, invalid_grant는 code/redirect URI를 확인하세요.'
  if ($_.ErrorDetails.Message) {
    try {
      $failure = $_.ErrorDetails.Message | ConvertFrom-Json
      Write-Host ('error={0}, error_code={1}' -f $failure.error, $failure.error_code)
    } catch { Write-Host '상세 오류를 읽을 수 없습니다. 원문 전체를 공유하지 마세요.' }
  }
} finally {
  $tokenBody.Clear()
  Remove-Variable code, clientSecret -ErrorAction SilentlyContinue
}
```

성공 후 다음 명령으로 필요한 카카오 access token만 클립보드에 복사한다. 전체 응답에는 refresh token, id token 등이 포함될 수 있으므로 출력·공유하지 않는다.

```powershell
if ($kakaoToken -and $kakaoToken.access_token) {
  Set-Clipboard -Value $kakaoToken.access_token
}
```

### A02 실행

1. Authorize에 **현재 게스트 SafeCall access token**을 넣는다. 만료됐다면 먼저 A03으로 갱신한다.
2. 새 Idempotency-Key를 생성한다.
3. 아래 installationId를 **A01에서 사용한 UUID**로 바꾼다. kakaoAccessToken에는 클립보드의 카카오 토큰만 붙여 넣는다. 예제의 한글 안내 문자열은 그대로 전송하지 않는다.

```json
{
  "installationId": "A01에서_사용한_UUID",
  "platform": "ANDROID",
  "appVersion": "1.0.0",
  "osVersion": "16",
  "kakaoAccessToken": "방금_발급한_카카오_access_token"
}
```

**판정:** A02=200, session.kind=KAKAO, userId 존재, SafeCall tokens 발급. 신규 회원은 PROFILE에서 시작하며 기존 회원은 저장 상태에 따라 다를 수 있다. profile의 null/UNKNOWN은 미입력 상태일 수 있다.

A02 성공 즉시 Authorize → Logout → **새 SafeCall tokens.accessToken**으로 Authorize한다. 기존 게스트 토큰을 계속 사용하지 않는다. 새 refresh token도 기존 값과 함께 교체한다. A05에서 kind=KAKAO를 확인한다.

붙여넣기를 마쳤으면 `Set-Clipboard -Value ''`로 현재 클립보드를 비우고 `Remove-Variable kakaoToken -ErrorAction SilentlyContinue`로 응답 변수를 제거한다. 클립보드 기록·동기화를 사용한다면 이전 항목도 별도 삭제해야 한다. 이것은 발급 토큰을 폐기하는 작업은 아니다.

카카오 요청 조건과 KOE010 조치는 [카카오 REST API 공식 문서](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [공식 오류 코드](https://developers.kakao.com/docs/ko/kakaologin/trouble-shooting)를 참고한다. Client Secret은 배포 앱·프론트 코드에 넣지 않고, 제품에서는 플랫폼에 맞는 SDK 또는 서버 OAuth 처리를 설계한다.

</details>

<details>
<summary><strong>4. 필수: U01 → U02 → U01 프로필 저장</strong></summary>

U01에서 현재 프로필과 `version`을 읽은 뒤 U02를 실행한다.

```json
{
  "name": "홍길동",
  "gender": "MALE",
  "birthDate": "2000-01-01",
  "phone": "01012345678",
  "expectedVersion": 1
}
```

예상 결과는 200, `confirmedAt` 생성, `version` 증가다. 성별·생년월일을 모르면 `gender: "UNKNOWN"`, `birthDate: null`을 명시한다. `birthDate` 필드를 생략하지 않는다. 카카오에서 받은 값과 같은 값은 KAKAO 출처를 유지하고, 바꾼 값은 USER_CONFIRMED, 지운 값은 UNKNOWN이다.

오래된 버전은 409 VERSION_CONFLICT, 잘못된 010 번호는 422 INVALID_PHONE, 존재하지 않거나 미래인 생년월일은 422 INVALID_BIRTH_DATE다. 이름의 개행·제어문자와 알 수 없는 필드는 400이다.

`expectedVersion`은 U02 JSON 본문에 넣는다. 저장 후 U01의 name/gender/birthDate/phone이 입력값과 일치하고 confirmedAt이 null이 아닌지 확인한다. 성별·생년월일이 필요한 검수는 테스트 정보로 진행한다.

</details>

<details>
<summary><strong>5. 필수: U07 → U08 → U07 연락망 등록</strong></summary>

U08에서 본인과 다른 번호를 등록한다.

```json
{"name":"김보호","relationship":"가족","phone":"01098765432"}
```

- 201이면 반환된 `id`, `slot`, `version`을 기록한다. 서버가 빈 슬롯 1 또는 2를 선택한다.
- 같은 키와 같은 정규화 내용으로 재시도하면 같은 연락처를 반환한다. 그 연락처가 이후 수정되었다면 현재 값을 반환하며, 삭제되었다면 404 CONTACT_NOT_FOUND다.
- 두 번째 보호자는 다른 번호와 새 키로 등록한다. 세 번째는 409 CONTACT_LIMIT_REACHED다.
- 본인이나 다른 보호자와 같은 번호는 409 CONTACT_PHONE_DUPLICATE다. U02에서 본인 번호를 보호자 번호로 바꾸는 것도 거절한다.
- U09는 경로에 저장한 `id`를 넣고 `name`, `relationship`, `phone`, `expectedVersion`을 전송한다. 200과 버전 증가를 확인한다.
- U10은 `contactId`, 현재 `expectedVersion`, 새 `Idempotency-Key`를 넣는다. 성공은 본문 없는 204다. 같은 키·대상·버전으로 다시 실행해도 204다.
- 다른 카카오 계정에서 원래 연락처를 수정·삭제하면 404다. 해당 계정으로 다시 Authorize한 후 검사한다.

연락처 삭제·수정 후 앱은 이전 메시지 작성 자료를 버리고 다시 조회해야 한다. 서버에는 문자 본문 스냅샷을 저장하지 않는다.

**이번 오류 재현과 해결:** 요청에 `"slot": 1`을 넣으면 허용되지 않은 필드로 400 INVALID_REQUEST가 발생한다. slot을 제거하고 수정된 새 행동에는 새 멱등 키를 쓴다. U08의 요청은 name/relationship/phone만 포함한다.

U08=201 후 U07=200에서 해당 id와 저장값을 확인한다. U09/U10은 추가 검수로 진행한다. U10은 expectedVersion을 query에 넣으며 삭제 후 U07에서 대상이 없어야 한다. 다음 단계에 연락처가 필요하면 다시 등록한다.

</details>

<details>
<summary><strong>6. 필수: U03 문서 준비 → U04/U05 동의</strong></summary>

### U03 입력 위치

| Swagger 칸 | 최초 조회 입력 |
| --- | --- |
| codes (query) | `PRIVACY_PROCESSING,AI_CALL,LOCATION_PROCESSING` 또는 빈칸(전체 조회) |
| If-None-Match (header) | 빈칸 |

문서 코드를 If-None-Match에 넣지 않는다. 200이어도 `items: []`이면 조회 조건에 맞는 발행 문서가 없는 상태이므로 동의 등록으로 넘어가지 않는다.

### 로컬 문서가 비어 있을 때

1. Workbench에서 서버가 사용하는 **로컬 DB 연결**인지 확인한다.
2. `SELECT DATABASE();`로 선택된 스키마를 확인한다. null이면 DB가 선택되지 않았다.
3. 로컬 DB 이름이 safecall인 경우 `USE safecall;`을 먼저 실행한다. 다른 이름이면 실제 설정을 따른다. 왼쪽 SCHEMAS에서 해당 DB를 더블클릭해도 된다.
4. [개발용 시드 SQL](../db/dev/seed-test-documents.sql)을 열고 문서가 없는 로컬 DB에 한 번 실행한다. 스키마 생성 SQL 전체를 다시 실행하지 않는다.
5. U03을 다시 조회한다. 위 세 codes로 조회하면 시드 기준 세 문서가 반환되고 code/version/isConsent/isRequired를 확인할 수 있어야 한다.

`Error Code: 1046. No database selected`는 3번으로 해결한다. 중복 오류가 나면 시드를 반복 삽입하거나 기존 문서를 삭제하지 말고 현재 문서와 버전을 확인한다. 합성 문서는 실제 약관이 아니므로 운영에 넣지 않는다.

### 동의와 재조회

U03에서 현재 버전을 확인하고 U05에 넣는다. 아래 예제의 버전은 로컬 합성 문서 기준이다.

```json
{
  "decisions": [
    {"code":"PRIVACY_PROCESSING","version":1,"action":"GRANTED"},
    {"code":"AI_CALL","version":1,"action":"GRANTED"}
  ]
}
```

LOCATION_PROCESSING은 선택 동의다. HELP·SOS_GUIDE 등 안내 문서는 동의 대상이 아니다.

- U04에서 사건이 없으면 NOT_DECIDED, `decisionVersion`/`recordedAt`은 null이다.
- U05 성공 후 해당 항목이 GRANTED, `isValid: true`인지 확인한다.
- `version`은 현재 문서 버전, `decisionVersion`은 마지막 선택 당시 문서 버전이다. 문서가 갱신되면 과거 GRANTED도 `isValid: false`가 된다.
- 동일 키·본문 재시도는 사건을 중복 생성하지 않고 현재 동의 상태를 반환한다. 같은 키로 다른 선택을 보내면 409 IDEMPOTENCY_CONFLICT다.
- 버전 오류가 포함된 배치는 전체 취소된다. 안내 문서에 동의하면 422 NOT_A_CONSENT_DOCUMENT다.
- 이미 GRANTED인 항목을 DECLINED로 바꾸면 409 WITHDRAWAL_REQUIRED다. 철회는 U06을 사용한다.

U03의 `ETag` 응답 헤더를 `If-None-Match`에 넣고 같은 목록을 재조회하면 304와 빈 본문이 나온다. 인증 응답은 계속 `Cache-Control: no-store`다.

LOCATION_PROCESSING까지 시험하려면 U03에 반환된 해당 version으로 세 번째 decision을 추가한다. 필수 통과 기준은 PRIVACY_PROCESSING/AI_CALL의 현재 버전에 대한 GRANTED와 isValid=true다. 200만 보고 통과시키지 않는다.

**ETag 추가 검수:** U03 응답의 ETag를 따옴표 포함 그대로 If-None-Match에 넣고 같은 codes로 재조회한다. 변경이 없으면 304와 빈 본문. 다시 전체 응답을 보려면 If-None-Match를 지운다.

</details>

<details>
<summary><strong>7. 필수: A06 → A07 회원 온보딩 완료</strong></summary>

각 단계마다 A06에서 현재 `step`과 `version`을 확인하고 A07의 `step`, `expectedVersion`에 그대로 넣는다.

| 현재 단계 | 진행 전 작업 | A07 추가 필드 |
| --- | --- | --- |
| PROFILE | U02로 기본 정보 확인 | 없음 |
| CONTACTS | U08 등록 또는 건너뛰기 | 없음 |
| CONSENTS | U03/U05에서 현재 필수 문서 동의 | 없음 |
| PERMISSIONS | 권한 안내 확인 | `permissionReview: {"microphone":"GRANTED","location":"DENIED"}` 등 실제 검수 상황 |
| SOS_GUIDE | SOS 안내 확인 | 없음 |
| MESSAGE_TEST | 메시지 테스트 단계 선택 | `testDecision: "SKIP"` 또는 `"FINISH"` |

각 A07 행동마다 새 멱등 키를 사용한다. 마지막 응답의 `step: "COMPLETE"`와 A05의 세션을 확인한다. 현재 메시지 작성 API는 후속 장이므로 Swagger 검수에서는 SKIP으로 진행할 수 있다.

예를 들어 A06이 PROFILE/version=1이면 다음을 보낸다. 실제 값이 다르면 반드시 교체한다.

```json
{"step":"PROFILE","expectedVersion":1}
```

PERMISSIONS에서는 아래 형식으로 보내되 version은 직전 A06 값으로 교체한다. true/false는 허용되지 않는다.

```json
{"step":"PERMISSIONS","expectedVersion":4,"permissionReview":{"microphone":"GRANTED","location":"DENIED"}}
```

MESSAGE_TEST에서는 아래 형식으로 SKIP을 명시한다.

```json
{"step":"MESSAGE_TEST","expectedVersion":6,"testDecision":"SKIP"}
```

각 응답 뒤 A06 재조회로 저장 여부를 확인한다. 409 ONBOARDING_STEP_MISMATCH이면 이미 지난 단계를 보내지 않았는지 확인한다. COMPLETE는 완료 상태이므로 더 advance하지 않는다. 최종 A06=200, step=COMPLETE, isAdvanceAllowed=false가 통과 기준이다.

</details>

<details>
<summary><strong>8. 필수: U11 → U12 → U11 설정 저장</strong></summary>

U11의 초기값은 RINGTONE이다. 현재 설정 버전을 U12에 넣는다.

```json
{"incomingAlertMode":"VIBRATE","expectedVersion":1}
```

200과 버전 증가를 확인하고 U11로 다시 조회한다. RINGTONE/VIBRATE/SILENT만 허용하며, 게스트는 403 LOGIN_REQUIRED다.

필드는 `alertMode`가 아니라 `incomingAlertMode`이며 expectedVersion도 본문에 넣는다. 기존 계정은 초기값과 다를 수 있으므로 현재 조회 결과부터 확인한다.

</details>

<details>
<summary><strong>9. 필수: A03 갱신 → A04 로그아웃 → A05 차단</strong></summary>

A03 `POST /api/v1/auth/refresh`는 Authorize 값 대신 본문의 refresh token으로 인증한다.

```json
{
  "refreshToken": "A01_또는_A02에서_받은_refreshToken"
}
```

성공하면 새 `accessToken`과 새 `refreshToken`이 나온다. Swagger 오른쪽 위 `Authorize`에서 `Logout`을 누르면 서버 로그아웃이 아니라 Swagger에 저장된 토큰만 지워진다. 기존 토큰을 지운 뒤 새 `accessToken`으로 다시 Authorize한다.

이미 갱신에 사용한 이전 refresh token을 다른 `Idempotency-Key`로 다시 보내면 `TOKEN_REUSED`가 나고 세션이 폐기될 수 있다. 의도한 재사용 탐지 테스트가 아니면 이전 refresh token은 버린다.

게스트 세션으로 A04를 호출하면 `403 LOGIN_REQUIRED`가 정상이다.

카카오 A02 성공 후에는 응답의 SafeCall `accessToken`을 Swagger `Authorize`에 넣고 A04 `POST /api/v1/auth/logout`을 실행한다.

```json
{}
```

성공하면 `204 No Content`가 나온다. 그 다음 A05를 다시 호출했을 때 `401 SESSION_EXPIRED`가 나오면 로그아웃 후 세션 차단까지 확인된 것이다.

갱신/로그아웃 요청에도 Swagger의 필수 Idempotency-Key를 넣는다. A03 성공 후 새 access token으로 A05=200을 확인한 다음 로그아웃한다. A04=204 후 같은 토큰의 A05=401 SESSION_EXPIRED이면 통과다. 추가 사용자 검수는 다시 A02로 로그인한 뒤 새 SafeCall 토큰으로 수행한다.

</details>

<details>
<summary><strong>10. 선택·마지막: 동의 철회와 데이터 정리 검수</strong></summary>

U06의 경로 `code`와 본문의 현재 문서 `version`, 새 멱등 키를 넣는다.

```json
{"version":1}
```

| 철회 코드 | 즉시 결과 및 후속 처리 |
| --- | --- |
| AI_CALL | 동의 효력 차단, 열린 통화 종료·grant 무효화, AI_DATA 접수. 정리 작업이 성별·생년월일과 통화 관련 기록을 삭제. 이름·번호·연락망 유지 |
| LOCATION_PROCESSING | LOCATION_DATA 접수. 영속 좌표가 없으므로 서버 정리 완료 처리. 계정·프로필·연락망 유지. 앱 메모리 정리는 앱 책임 |
| PRIVACY_PROCESSING | ACCOUNT 접수 후 계정 API 접근 차단. **실제 계정 삭제·키 폐기·카카오 연결 해제·R03 상태 조회는 6장 후속 구현** |

AI_DATA 정리 중 재동의·성별/생년월일 저장은 409 DATA_CLEANUP_PENDING이다. 정리 후 해당 정보를 다시 저장하려면 AI_CALL에 재동의해야 한다. 기본 로컬 정리 주기는 1초이며 DB 장애가 있으면 재시도한다.

접수증의 `receiptToken`은 별도 보관한다. 같은 키·본문으로 60초 이내 재시도하면 같은 접수증을 반환한다. 시간이 지나면 409 DELETION_RECEIPT_EXPIRED다. PRIVACY_PROCESSING 철회 직후에도 이 제한된 재조회만 허용한다. 다른 API는 ACCOUNT_DELETION_PENDING으로 차단되므로 계정 철회 테스트를 마지막에 실행한다. 6장 worker가 아직 없으므로 현재 ACCOUNT 작업은 PENDING에 남는다.

이 단계는 앞선 정상 흐름을 모두 마친 별도 테스트 계정에서 수행한다. 계정 철회를 정상 흐름 중간에 넣지 않는다. 접수 응답만으로 실제 삭제 완료를 판정하지 않는다. ACCOUNT 후속 구현은 현재 검수 완료로 표시할 수 없다.

</details>

<details>
<summary><strong>11. 오류별 판단 기준 — 이번 시행착오 포함</strong></summary>

| 증상 | 확인 및 조치 | 재검수 기준 |
| --- | --- | --- |
| A01 400 bootstrapSecret | 2장의 난수 생성 JSON 전체 사용. 누락/짧은 값 금지 | 201과 GUEST |
| A01/A02 401 AUTHENTICATION_REQUIRED | 유효한 기존 설치 세션에는 현재 서버 토큰 필요. 독립 테스트만 새 설치로 시작 | 로그인 성공 후 A05 |
| PowerShell Fill 메서드 없음 | .NET 호환 Create()/GetBytes() 사용 | 난수 생성 성공 |
| PowerShell `>>`만 표시 | 닫는 중괄호·따옴표 확인. 불완전 입력은 Ctrl+C 후 코드 블록 전체 실행 | 구문 오류 없음 |
| StreamAlreadyRedirected | 복사한 `>>`, PS 프롬프트, 이전 오류 출력 제거 | 구문 오류 없음 |
| URL 관련 오류 | Markdown 링크 문법을 복사하지 않고 코드 블록의 순수 URL 사용 | 요청 전송 성공 |
| 콜백 404 | localhost 콜백의 주소창 code 유무 확인 | 카카오 토큰 교환 성공 |
| 카카오 401 | 상태만으로 원인 확정 금지. KOE010이면 client_secret 누락/불일치 확인 | 토큰 응답의 access_token 존재 |
| invalid_grant | 새 인가 code, 발급 시와 동일한 redirect_uri 확인 | 새 교환 성공 |
| U08 400 INVALID_REQUEST | slot 제거. name/relationship/phone만 전송 | 201 후 U07 저장값 |
| U03 codes 위치 착오 | codes는 query, If-None-Match는 최초 빈칸 | 요청 URL에 codes 반영 |
| U03 200 items=[] | DB 연결·현재 발행 문서·필터 확인, 빈 로컬 DB만 시드 적용 | 예상 문서 반환 |
| MySQL 1046 | USE 실제DB이름 또는 SCHEMAS 더블클릭 | SELECT DATABASE()가 대상 DB |
| 409 VERSION_CONFLICT | 해당 자원 재조회 후 새 버전으로 수정 | 저장 후 버전 증가 |
| 409 ONBOARDING_STEP_MISMATCH | A06 현재 step/version 확인 | 다음 단계로 진행 |
| 409 IDEMPOTENCY_CONFLICT | 같은 키에 다른 본문 금지 | 새 행동에 새 키 |
| 403 LOGIN_REQUIRED | 게스트인지 A05 확인, 회원 검수는 A02 후 새 토큰 사용 | 회원 API 200 |
| 로그아웃 후 401 SESSION_EXPIRED | 의도한 폐기 확인 결과 | 통과, 계속하려면 재로그인 |
| 429 RATE_LIMITED | Retry-After 준수 | 제한 해제 후 동일 요청 재시도 |

오류 보고에는 API ID, HTTP 상태, 오류 code, 토큰을 제외한 필드명, request-id만 남긴다. 실제 비밀값을 포함한 Curl·전체 응답은 공유하지 않는다.

</details>

<details>
<summary><strong>12. 프론트 연동: 값의 수명과 토큰 유실 처리</strong></summary>

### 프론트에서 값 생성·저장 및 세션 유지

프론트는 최초 설치 실행 시 값을 자동 생성해 저장한다. 이 문서의 PowerShell 절차는 Swagger 수동 검수용이며 앱 사용자가 실행할 필요는 없다.

| 값 | 생성과 수명 |
| --- | --- |
| `installationId` | 최초 실행 시 UUID 생성 후 설치 단위로 저장·재사용한다. API 호출마다 새로 만들지 않는다. |
| `bootstrapSecret` | 암호학적으로 안전한 난수 32바이트를 Base64URL로 인코딩하고 플랫폼 보안 저장소에 저장한다. 예제 상수를 제품 코드에 넣지 않는다. |
| `Idempotency-Key` | 새 행동마다 UUID 생성. 같은 요청을 네트워크 오류로 재시도할 때는 키와 본문을 그대로 유지한다. |
| 서버 `accessToken`, `refreshToken` | A01/A02/A03 응답으로 받아 플랫폼 보안 저장소에 저장한다. 갱신 성공 시 두 토큰을 함께 교체한다. |

```text
앱 실행
  → 저장된 설치 ID와 bootstrap secret 확인
  → 최초 설치이면 생성·저장
  → 저장된 서버 토큰 확인
      ├─ access token 사용 가능 → 기존 세션 사용
      ├─ access token 만료, refresh token 사용 가능 → A03 갱신 후 두 토큰 저장
      └─ 사용 가능한 토큰 없음 → 신규 설치인지, 기존 설치의 토큰 유실인지 구분
          ├─ 신규 설치 → A01 호출 후 토큰 저장
          └─ 기존 설치 → 세션 만료·폐기 여부 및 복구 흐름 확인
```

앱 실행마다 A01을 호출하지 않는다. 게스트에서 카카오 로그인으로 전환할 때는 같은 `installationId`를 사용하고, 게스트 세션의 현재 서버 access token을 Authorization 헤더에 보내거나 `currentRefreshToken`을 본문에 보낸다. `kakaoAccessToken`에는 별도로 카카오가 발급한 토큰을 넣는다. A02 성공 후 서버 토큰을 새 응답으로 교체한다.

설치 ID만 남고 토큰을 잃어버렸는데 서버의 기존 세션이 유효하면 현재 구현은 401로 새 세션 생성을 거절한다. bootstrap secret만으로 기존 세션의 소유를 증명할 수는 없다. 이 상황의 제품 복구 정책은 프론트 연동 시 별도로 결정해야 하며, 401마다 설치 ID를 자동 재생성하는 처리를 넣지 않는다. 위 수동 검수에서 새 ID를 만드는 것은 독립된 새 설치를 시험하기 위한 절차다.

프론트가 저장하는 값은 서버 비밀키(JWT_SECRET/HMAC_SECRET/KAKAO_CLIENT_SECRET)가 아니다. 모바일에서는 플랫폼 보안 저장소를 사용하고 웹은 별도 인증 저장·쿠키·CSRF 정책을 설계한다. SDK/서버 OAuth의 구체 구현은 이 수동 검수 문서의 범위 밖이다.

</details>

<details>
<summary><strong>13. 최종 체크리스트와 결과 기록</strong></summary>

- [ ] A01 201, A05 GUEST, 게스트 A06 COMPLETE
- [ ] A02 200, A05 KAKAO, 새 서버 토큰으로 교체
- [ ] U02 200, U01 재조회 값/confirmedAt/version 확인
- [ ] U08 201, U07에 생성 연락처 확인
- [ ] U03 예상 문서 존재, U05 이후 U04 현재 필수 동의 isValid=true
- [ ] 회원 A06 COMPLETE, isAdvanceAllowed=false
- [ ] U12 200, U11에서 설정값/버전 확인
- [ ] A03 갱신 후 새 토큰의 A05 200
- [ ] A04 204, 폐기된 세션의 A05 401
- [ ] 선택: U09/U10, 중복·최대 2명·타인 접근 거절, ETag 304, 버전 충돌
- [ ] 선택: U06 AI/위치 정리와 ACCOUNT 접근 차단. 미구현 후속 작업 별도 기록
- [ ] 공유 자료의 토큰·개인정보 제거, 로컬 테스트 종료 후 인증 값 정리

| API/시나리오 | 기대값 | 실제 상태/안전한 요약 | 재조회 확인 | 판정 |
| --- | --- | --- | --- | --- |
| 예: U08→U07 | 201→200, 연락처 저장 | 상태와 필드명만 기록 | 완료/미완료 | PASS/FAIL/미실행 |

선택 테스트를 실행하지 않았다면 미실행으로 남긴다. 문서 작성이나 과거 자동 테스트 통과를 이번 수동 검수 통과로 대체하지 않는다.

</details>

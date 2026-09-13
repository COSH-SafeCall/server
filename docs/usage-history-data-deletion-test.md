# 이용 기록·데이터 삭제 검증 · R01~R03

[문서 목록](README.md) · [공통 요청 방법](swagger-test-guide.md) · v4.2-web-mvp API 6장

## 이용 기록

`GET /api/v1/me/usage-history?limit=20&cursor=...`는 회원의 모든 웹 세션에서 종료·실패한 통화만 반환한다. limit은 기본 20, 최대 100인 양의 정수다. 첫 페이지에서는 cursor를 생략하고 이후에는 응답의 nextCursor를 그대로 전달한다. null이면 마지막 페이지다.

응답은 `{items,nextCursor}`이며 각 항목은 `{id,state,startMode,scenarioCode,counterpartCode,displayName,createdAt,answeredAt,endedAt,endReason}`다. 정렬은 createdAt 내림차순, 같은 시각에서는 UUID 바이트 내림차순이다. 커서는 사용자별 HMAC 서명으로 보호하고, 잘못된 형식·변조·다른 회원의 커서는 400 INVALID_CURSOR로 거절한다. 삭제된 행을 가리키는 커서도 정렬 경계로 사용할 수 있다.

게스트 이력은 합치지 않는다. 열린 통화·음성·대화·번호·메시지 결과·재개 handle·pageKey·lease·연결 토큰은 제공하지 않는다. 이력에서 통화를 재개할 수 없다. 응답은 Cache-Control: no-store이며 ETag/304를 사용하지 않는다.

## 삭제 접수

`POST /api/v1/me/data-deletions`에 세션 쿠키, Origin, X-CSRF-Token, Idempotency-Key와 아래 본문을 전달한다.

```json
{"scope":"USAGE_HISTORY","isConfirmed":true}
```

클라이언트 scope는 ACCOUNT 또는 USAGE_HISTORY만 허용한다. AI_DATA·LOCATION_DATA는 기존 U06 동의 철회에서 생성한다. 프론트엔드는 삭제될 데이터와 되돌릴 수 없는 항목을 먼저 안내하고 확인을 받은 뒤 요청해야 한다.

- USAGE_HISTORY: 해당 회원의 어느 웹 세션에든 열린 통화가 있으면 409 CALL_ALREADY_OPEN. 접수 시점 cutoffAt까지 생성된 종료·실패 통화와 관련 사건을 삭제하며, 이후 생성한 통화와 프로필·연락망·동의는 유지한다.
- ACCOUNT: 동일 계정의 A02 REAUTH 완료 후 5분 이내에 새 삭제 확인과 새 멱등 키로 요청한다. 재인증 누락·만료·카카오 인앱 브라우저는 403 REAUTHENTICATION_REQUIRED. 접수와 함께 계정 사용을 차단하고 모든 회원 세션의 열린 통화를 DATA_DELETION으로 종료한다.

성공은 202 DeletionView이며 필드는 `{id,scope,status,requestedAt,dueAt,completedAt,errorCode}`다. dueAt은 접수 후 24시간의 완료 목표다. 접수증 원문은 `__Host-safecall-deletion` Secure/HttpOnly/SameSite=Lax/Path=/ 쿠키(최대 30일)에만 발급한다. JSON·URL·localStorage에 복사하지 않는다.

같은 멱등 키·본문은 60초 동안 같은 접수증과 현재 작업 상태를 재생한다. 같은 키의 다른 scope는 409 IDEMPOTENCY_CONFLICT, 재생 기간 만료는 409 DELETION_RECEIPT_EXPIRED다. 같은 범위의 진행 중 작업은 새로 만들지 않는다. 유효한 재생 자료가 있으면 접수증도 재사용하고, 만료된 경우에는 소유 회원에게 새 접수증을 발급한다. 새 접수의 응답 재생 구간이 끝나기 전에는 ACCOUNT·USAGE_HISTORY 로컬 삭제를 시작하지 않는다.

## 상태 조회와 처리 단계

`GET /api/v1/data-deletions/{jobId}`는 소유 회원의 유효한 세션 또는 해당 작업의 접수증 쿠키로 조회한다. 탈퇴 후에도 접수증으로 조회할 수 있으며 잘못된 작업·타인·다른 접수증·만료 접수증은 404다. 쿠키는 가장 최근 접수증을 가리키므로, 계정이 남아 있으면 회원 세션으로 이전 작업도 조회할 수 있다. 탈퇴 후 접수증을 잃거나 만료되면 재인증으로 복구할 수 있다고 안내하지 않는다.

| 상태 | 의미와 화면 안내 |
|---|---|
| PENDING / PROCESSING | 삭제 처리 대기·진행. 현재 worker는 짧은 로컬 트랜잭션 안에서 완료 상태로 전환한다 |
| FAILED | DB 작업의 확정 롤백과 복호화 키 유지를 확인한 상태. “정보를 삭제하지 못했습니다. 기존 데이터는 유지됩니다. 다시 시도해 주세요.” |
| LOCAL_DELETED | ACCOUNT 로컬 데이터 삭제 완료. “SafeCall 정보는 삭제됐으며 외부 연결 정리를 진행하고 있습니다.” |
| COMPLETED | 해당 범위의 삭제와 필요한 외부 정리 완료 |

로컬 DB 삭제와 상태 전환은 같은 트랜잭션에서 커밋한다. 커밋 결과가 불명확하면 FAILED로 단정하지 않고 다음 주기에 작업 상태를 다시 읽는다. FAILED의 새 요청은 새 작업을 만들며, 종료된 통화나 철회된 동의를 자동 복구하지 않는다. ACCOUNT 접수 전 회원 상태는 별도 임시 키로 암호화한다. 로컬 실패 시 그 상태 복구와 재가입 차단 해시 제거도 원자적으로 수행하고 복구 자료를 폐기한다. 이전 구현에서 생성되어 복구 자료가 없는 작업은 상태를 추측하지 않고 PENDING에서 재시도한다.

ACCOUNT 로컬 삭제는 webSession → appUser 순서이며, 개인 키와 별도인 작업 키로 카카오 subject·개인 키 참조를 암호화해 보존한다. 외부 worker는 짧은 DB 트랜잭션에서 암호화된 작업에 60초 선점 기한을 기록한 뒤, DB 트랜잭션 없이 개인 키 폐기와 카카오 연결 해제를 수행한다. 실패·응답 유실·프로세스 중단 시 LOCAL_DELETED를 유지하고 선점 기한 이후 재조회·재시도한다. 완료 시 cleanupCipher·cleanupKeyRef·accountSubjectHash를 제거하고 작업 키를 커밋 후 폐기한다. 운영 외부 키 저장소의 폐기 재시도·관측은 별도 배포 조건이다.

## 카카오 설정과 실제 환경 검수

서버 전용 `KAKAO_ADMIN_KEY`에 Service app admin key를 주입한다. 키가 없거나 연결 해제가 실패해도 삭제 완료로 가장하지 않으며 LOCAL_DELETED에서 재시도한다. REST API 키·client secret과는 다른 값이다. [카카오 공식 연결 해제 규격](https://developers.kakao.com/docs/ko/kakaologin/rest-api#unlink)에 따라 고정 HTTPS endpoint에 admin 인증과 target_id_type=user_id를 사용한다. 재시도 시 [400/-101](https://developers.kakao.com/docs/ko/kakaologin/trouble-shooting)은 이미 앱에 연결되지 않은 사용자로 처리한다.

1. 합성 회원 두 명과 같은 회원의 두 브라우저에서 이력 분리·정렬·페이지 이동을 확인한다.
2. 이력 삭제 전 다른 브라우저의 열린 통화를 확인하고, 종료 후 접수한다. 60초 재생 구간과 새 통화 보존을 확인한다.
3. 테스트용 계정에서 명시적 REAUTH·삭제 확인 후 ACCOUNT를 접수한다. 로컬 삭제 전 재생, 이후 접수증만으로 LOCAL_DELETED/COMPLETED 조회를 확인한다.
4. 카카오 오류·키 폐기 오류 시 LOCAL_DELETED 유지, 재시도 완료 후 재가입 허용을 확인한다.
5. 실패·완료·접수증 분실에 대한 화면 문구와 삭제 전 범위 안내를 검수한다.

자동 테스트는 임시 MySQL과 모의 카카오 응답을 사용한다. 실제 카카오 연결 해제, 브라우저의 Secure 쿠키 수용, 여러 서버의 장애 복구 및 삭제 기록을 반영한 백업 복원은 별도 검수다. 실제 개인정보나 인증 값을 테스트 기록에 남기지 않는다.

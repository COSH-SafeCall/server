# 이용 기록·동의 철회·삭제

[테스트 시작](README.md) · 회원 기능 검증을 마친 **테스트 계정**에서 실행

## 1. 이용 기록 R01

1. 회원 통화를 종료한다. 다른 브라우저도 같은 회원으로 로그인하면 해당 회원의 종료·실패 이력을 조회할 수 있다.
2. `GET /me/usage-history?limit=20`을 호출하여 200의 items/nextCursor를 확인한다.
3. nextCursor가 있으면 그대로 cursor에 넣어 다음 페이지를 조회한다. null이면 마지막 페이지다.

limit은 기본 20, 최대 100이다. createdAt 내림차순, 같은 시각이면 UUID 바이트 내림차순이다. 변조·다른 회원의 커서는 400 INVALID_CURSOR다. 열린 통화·게스트 이력·음성·대화·연결 token은 반환하지 않으며 이력에서 재개할 수 없다. 응답은 no-store다.

## 2. 이용 기록 삭제 R02 → R03

다른 브라우저를 포함해 회원의 열린 통화를 모두 종료한 뒤 진행한다.

1. R02 `POST /me/data-deletions`에 현재 CSRF·새 Idempotency-Key와 아래 본문을 보낸다.
2. 202의 id를 보관하고 R03 `GET /data-deletions/{id}`로 상태를 조회한다.
3. 60초 응답 재생 구간이 끝나고 정리가 완료되면 COMPLETED를 확인한다. R01을 다시 조회하여 접수 cutoff까지 생성된 완료 이력이 사라졌는지 확인한다.

```json
{"scope":"USAGE_HISTORY","isConfirmed":true}
```

접수 이후 생성한 통화·프로필·연락망·동의는 유지한다. 접수 시 열린 통화가 있으면 409 CALL_ALREADY_OPEN이다. 같은 키·본문의 재시도는 60초간 같은 작업과 접수증을 재생하며 다른 본문은 409 IDEMPOTENCY_CONFLICT, 재생 기간이 끝나면 DELETION_RECEIPT_EXPIRED다.

## 동의 철회 U06

다른 기능 검증을 끝낸 후 현재 동의와 범위를 확인하고 `POST /me/consents/{code}/withdrawal`에 `{}`, 현재 CSRF·새 Idempotency-Key를 보낸다. 202의 id로 R03을 조회한다.

| code | 작업·확인 |
|---|---|
| AI_CALL | AI_DATA. 통화 즉시 종료, 정리 완료 시 AI 관련 이력·성별·생일 제거. 이름·전화번호·보호자는 유지 |
| LOCATION_PROCESSING | LOCATION_DATA. 관련 정리 후 위치 없는 M01 작성 가능 |
| PRIVACY_PROCESSING | ACCOUNT. 아래와 동일하게 최근 REAUTH가 필요하며 계정 삭제가 진행됨 |

정리 중 해당 범위의 재동의·생성은 차단한다. 202는 접수 성공이고 물리 삭제 완료가 아니다. AI/위치 재동의는 정리 완료 후 현재 고정 문서 버전으로 U05를 사용한다. 철회한 동의와 종료 통화가 자동 복구되지는 않는다.

## 3. 계정 삭제: REAUTH → A05 → R02 → R03

1. A02 `{"purpose":"REAUTH"}`로 같은 계정 재인증을 마치고 A05로 CSRF를 갱신한다.
2. 5분 안에 삭제 범위를 다시 확인한 뒤 R02에 `{"scope":"ACCOUNT","isConfirmed":true}`와 새 Idempotency-Key를 보낸다. 이 테스트에서 PRIVACY_PROCESSING 철회와 ACCOUNT 접수를 둘 다 실행할 필요는 없다.
3. 202의 id로 R03을 조회한다. 브라우저가 HttpOnly 접수증 쿠키를 자동 전송하므로 쿠키를 복사하거나 JSON에서 token을 찾지 않는다.
4. PENDING 이후 LOCAL_DELETED, 외부 키 폐기·카카오 연결 해제까지 끝나면 COMPLETED를 확인한다. 세션이 사라진 뒤에도 해당 접수증으로 조회할 수 있다.

서버의 `KAKAO_ADMIN_KEY`가 없거나 외부 정리가 실패하면 LOCAL_DELETED에서 재시도한다. 일반 LOGIN은 재인증 조건을 충족하지 않으며 알려진 카카오 인앱 브라우저도 민감 작업을 거절한다. 접수 후 계정 사용을 차단하고 모든 회원 세션의 열린 통화를 DATA_DELETION으로 종료한다.

## 상태와 접수증

DeletionView는 `{id,scope,status,requestedAt,dueAt,completedAt,errorCode}`다. dueAt은 접수 후 24시간의 목표 기한이며 완료 증명이 아니다.

| status | 의미 |
|---|---|
| PENDING / PROCESSING | 접수·로컬 처리 대기 또는 진행 |
| FAILED | 로컬 작업의 확정 롤백으로 삭제 대상 데이터와 키 유지. 확인 후 새 요청 가능 |
| LOCAL_DELETED | ACCOUNT 로컬 삭제 완료, 외부 정리 재시도 중. 삭제 실패로 표시하지 않음 |
| COMPLETED | 해당 범위의 삭제와 필요한 외부 정리 완료 |

R03은 소유 회원의 유효 세션 또는 해당 작업 접수증으로 조회한다. 타인·잘못된 ID·다른 접수증·만료 접수증은 404다. 접수증 쿠키는 가장 최근 작업을 가리키며 최대 30일이다. 계정이 남아 있으면 회원 세션으로 이전 작업도 조회할 수 있다. 탈퇴 뒤 접수증을 잃거나 만료되면 재인증으로 복구할 수 없다.

## 장애 검증

DB 실패 시 데이터·키·상태 원자성, 외부 오류 시 LOCAL_DELETED 유지, 실패 100건 뒤 정상 작업 진행, 재시작·다중 작업자 경쟁은 [격리 자동 테스트](../verification.md)로 확인한다. Swagger에서 서비스 DB에 장애를 주입하지 않는다. 실제 연결 해제와 외부 키 폐기·복원 검수는 [배포 조건](../deployment.md)을 따른다.

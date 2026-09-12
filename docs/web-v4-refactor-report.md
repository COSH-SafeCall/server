# 4장까지 리팩토링 결과

[문서 목록](README.md) · 기준: v4.2-web-mvp / 2026-09-12

기존 작업 트리의 인증·사용자·홈·통화 코드를 공통 설계와 API 1~4장의 28개 HTTP 작업에 맞췄다. 전체 33개 작업 중 5~7장 신규 API는 후속 범위다. 근거 문서와 실행 진입점은 [문서 목록](README.md), 최종 실행 수량·시각은 [검증 결과](verification.md)에서 관리한다.

## 핵심 변경

| 영역 | 이전 | 현재 |
|---|---|---|
| 인증 | 설치/기기 세션, JWT·refresh, 카카오 SDK token 입력 | webSession·oauthAttempt·oauthConsent, 보안 쿠키, 안정적인 CSRF, 서버 OAuth code 교환 |
| OAuth·민감 작업 | 일반 로그인 증명 | 서비스 동의 선행, state 일회성 선점과 교환 후 재검증, 동일 계정 REAUTH 후 5분 |
| 프로필 U02 | PUT·이전 입력 모델 | PATCH 6필드, nullable 성별/생일, 확인 값·version, 이름·전화번호·날짜 검증 |
| 문서 U03 | 목록/codes | `/documents/{code}` 단건, 선택 version, ETag/304 |
| 연락망 U09/U10 | PUT·이전 삭제 계약 | PATCH 및 DELETE 본문의 expectedVersion, 소유권·최대 2명·번호 중복 검증 |
| 철회 U06 | 이전 경로·응답 | `/withdrawal`, 202 DeletionView 7필드, HttpOnly 접수증 쿠키, 60초 재생 |
| 동의·홈 | 이전 재동의 단계와 기능 조건 | 최초 동의 문서 버전 고정, 범위별 정리/재동의 차단, 인구통계 마스킹, 열린 통화·정리 상태 반영 |
| 통화 | 기기 세션·단일 연결 중심 | 세션+페이지 키, generation/purpose별 grant, C07 재개 및 새 사건 |
| 시간·재개 | 이전 TTL·heartbeat | 최대 600초/모델/세션 한도 최솟값, 30초 lease·5초 heartbeat, 전체 재개 1회, 정책 스냅샷 |
| 발급·저장 | 이전 단기 연결 처리 | 10초 기준 ISSUING 결과 불명, 같은 grant 재발급 금지, 사용/종료 시 암호문 제거 |
| 멱등성 | 이전 인증·리소스 범위 | 소유자·대상 5요소 HMAC 튜플, 고정 operation 코드, 현재 리소스 재조회 |
| 삭제·보존 | 이전 정리 처리 | 데이터 삭제와 상태 원자 커밋, 미완료 가입·세션·통화·운영 이벤트 보존 경계 |
| 설정 | 실제 기본값이 입력된 예시 | 32개 항목이 모두 빈 `.env.example` 양식, 기본값은 주석 안내 |

## DB와 구조

[서버 DDL](../db/schema-mysql.sql)은 [최종 물리 DDL](../../design/DB_물리_설계_최종.sql)과 바이트 단위로 일치한다. 실제 MySQL에서 19테이블·189컬럼·171제약을 확인했다. 해시는 [결과 JSON](verification-web-v4.json)에 기록했다. 새 DB 설치용이며 ALTER/데이터 이관 스크립트가 아니다.

JPA Entity 대신 JDBC repository와 Java record를 사용한다. 관련 SQL·행 매핑·DTO를 수정했으며 읽기 record는 필요한 컬럼만 투영한다. UUID는 swap 없는 BINARY(16), 시간은 UTC DATETIME(6), 개인정보는 Cipher/HMAC/외부 keyRef 구조다.

주요 구성은 OAuthService/OAuthTransactions, WebPolicy/WebCookies, CallPolicy, TransientKeys, AccountLocalCleanup, WebRetention이다. 이전 TokenService/AuthService와 인증·사용자 테스트를 교체했다. 잠금 순서는 계정→세션→통화→grant이며 외부 호출은 DB 잠금 밖에서 수행한다.

## 삭제와 키 수명

AI_DATA/LOCATION_DATA는 관련 로컬 변경과 COMPLETED를 함께 커밋한다. ACCOUNT는 접수증 재생 60초 후 세션→회원 순서로 삭제하고 LOCAL_DELETED를 함께 커밋한다. 외부 정리용 subject는 별도 임시 키로 암호화하고 회원 키는 커밋 후 폐기한다.

비회원 세션은 만료/폐기 1시간 후, 회원 세션은 만료/폐기 30일 후 정리 대상이다. 종료 통화는 30일, 운영 이벤트는 14일, 완료 삭제 작업은 완료 30일 기준이다. 24시간 미완료 가입은 ACCOUNT 정리 경로에 들어간다. 동의·계정·세션 삭제로 더 일찍 정리될 수 있다.

재검토에서 확정 ROLLED_BACK에만 새 키를 폐기하도록 수정했다. UNKNOWN은 키를 보존하고 결과 확인을 기다린다. OAuth 키 읽기 전에 정리 콜백을 등록하여 읽기 실패 시 누수를 막았으며, WebPolicy 문자열 출력은 redacted로 고정했다.

## 검증과 전환 조건

[자동 검증](verification.md)은 인증/CSRF/OAuth, 입력·소유권·version, 멱등성, 동시 통화·재개, 시간 경계, 예약 정리와 DB·키 롤백을 포함한다. HMAC은 독립 계산한 고정 벡터와 대조했다. 실행 JAR 및 diff 공백 검사도 통과했다. 실제 서비스 DB와 로컬 `.env`는 변경하지 않았다.

중간 구현의 멱등 scopeHash에는 중복 용도 접두부가 있었다. 최종 형식은 명세의 길이 접두부 5요소 튜플 자체를 HMAC 입력으로 사용하므로 이전 중간 기록과 호환되지 않는다. 해당 중간 구현을 적용한 환경은 기존 멱등 기록의 24시간 재생 기간을 고려해 전환한다.

ACCOUNT 외부 정리와 R03, 5~7장 API, 운영 외부 키 저장소 및 실제 카카오/Gemini·브라우저 검수는 [배포 조건](aws-deployment-readiness.md)에 남아 있다. LOCAL_DELETED를 외부 정리까지 완료한 상태로 표시하지 않는다.

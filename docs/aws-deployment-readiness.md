# 운영 배포 준비

[문서 목록](README.md) · v4.2-web-mvp

현재 서버는 웹 OAuth와 통화 API를 구현했으나 **외부 UserKeyStore가 미구현이므로 prod 시작을 차단**한다. 이 문서는 완료된 배포 기록이 아니라 현재 코드의 배포 조건이다.

## 프로필과 설정

| 항목 | local | prod |
|---|---|---|
| 선택 | 프로필 미지정 시 기본 | 실행 환경에 `SPRING_PROFILES_ACTIVE=prod` 명시 |
| 환경 입력 | 공통 설정 + application-local.yml + 로컬 `.env` | 공통 설정 + application-prod.yml + 외부 주입 |
| 키 저장 | FileUserKeyStore, `.keys` 기본값 | 정확히 하나의 외부 UserKeyStore 구현 필요 |
| Swagger | 기본 활성, 선택 해제 | 기본 비활성 |
| DB | 개발 연결 | 전용 계정과 TLS `VERIFY_IDENTITY` |

local/prod를 함께 활성화하면 실패한다. 환경변수별 의미는 [환경 설정](environment-setup.md)에서 관리한다. `.env.example`은 빈 양식이며 배포용 credential 파일이 아니다.

## 배포 전에 완성할 항목

| 항목 | 현재 구현 | 남은 작업 |
|---|---|---|
| 키 저장 | DB에는 keyRef, local에는 사용자/임시 키 | 외부 저장소, 모든 서버의 동일 참조 해석, 32바이트 키 반환 계약, 폐기 재시도·관측 |
| 비밀값 | 환경변수 입력 | 운영 secret 주입·접근 권한. 기존 암호문/HMAC 식별값의 키 연속성 유지 |
| DB | 최종 신규 설치 DDL, 앱 DML | 운영 계정·TLS 신뢰 설정, 기존 데이터 이관, 실제 Linux DB 대소문자 설정 검증 |
| HTTPS·프록시 | 단일 WEB_ORIGIN, 전달 헤더 미신뢰, getRemoteAddr 기반 제한 | 실제 인입 프록시와 직접 접근 제한, 신뢰 범위에 맞춘 클라이언트 IP 추출 |
| 웹 보안 | 보안 쿠키, CSRF/Origin, CSP nonce, Permissions-Policy | 배포 HTML에 nonce 연결, 브라우저 검수, callback의 SPA fallback 제외 |
| 카카오 | authorization 시작·code 교환·callback·REAUTH 구현 | 운영 앱의 정확한 HTTPS callback 등록, scope·동일 계정 재인증 검수 |
| Gemini | 단기 token·연결 제약·재개 처리 | 출시 모델/API/voice/instruction/resumption 검수 자료와 설정 대조 |
| 안심 메시지 | M01 작성 자료·자격 재검증, 미설정 지도 템플릿은 null | Naver 템플릿·좌표 순서·HTTPS 호스트·지원 브라우저 검수 후 MESSAGE_MAP_* 등록. 실제 작성 화면·위치 취득 검수 |
| ACCOUNT 삭제 | 로컬 삭제+LOCAL_DELETED, 별도 외부 정리 암호문 | 카카오 연결 해제·정리 키 폐기·재시도·R03 조회 구현 |
| 복구·관측 | 정리 작업 및 안전한 오류 응답 | 상태 확인, 여러 서버의 작업 경쟁, DB·외부 키 백업 복원과 삭제 기록 재적용 검수 |

커밋 결과 불명 상태에서는 키를 보존하고 DB 결과를 먼저 확인한다. 로컬 삭제 후 외부 정리 오류를 FAILED로 표시하지 않는다. 백업 복원 뒤 폐기한 키나 삭제 데이터를 다시 서비스하지 않도록 복원 절차에 반영한다.

## 배포 계층의 로그

프록시·APM·access log에서 OAuth code/state 쿼리, Cookie/Authorization/X-CSRF-Token/X-Call-Page-Key, 개인정보 본문과 provider 원문 응답을 제외한다. 앱 로그 정책만으로 인입 계층의 수집이 차단되지는 않는다.

## 검수 결과 기록

실제 환경의 모델/API 버전, 브라우저·OS 버전, 검수 일시와 결과를 남긴다. [자동 테스트 결과](verification.md)를 외부 연동·운영 배포 성공으로 대체하지 않는다. 동의 문서 최초 버전 고정과 통화 정책 버전은 [최종 운영 정책](../../design/최종_운영_정책.md)을 따른다.

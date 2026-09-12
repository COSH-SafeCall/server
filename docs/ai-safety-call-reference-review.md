# 통화 명세와 코드 대응

[문서 목록](README.md) · v4.2-web-mvp

통화 구현의 검토 진입점이다. 이전 참고 서버의 token endpoint와 당시 구현 계획 대신, 현재 C01~C07이 어느 코드에서 처리되는지 정리한다.

## 구현 위치

| 계약 | 주요 코드 | 검토할 동작 |
|---|---|---|
| C01~C07 HTTP/DTO | [CallController](../src/main/java/com/safecall/service/call/api/CallController.java), [CallDtos](../src/main/java/com/safecall/service/call/api/CallDtos.java) | cookie·페이지 키·멱등 헤더, 조건부 grantId/errorCode, 200/202 응답 |
| 소유권·상태·정책 | [CallService](../src/main/java/com/safecall/service/call/service/CallService.java), [CallPolicy](../src/main/java/com/safecall/service/call/service/CallPolicy.java) | 세션→통화 잠금, 현재 동의, 최대 시간, lease, 재개 예산 |
| DB 제약·사건 | [CallRepository](../src/main/java/com/safecall/service/call/repository/CallRepository.java), [DDL](../db/schema-mysql.sql) | 열린 통화·진행 중 grant UNIQUE, generation, 사건 중복, 정책 스냅샷 |
| 발급·만료 | [CallWorker](../src/main/java/com/safecall/service/call/service/CallWorker.java) | DB 선점 후 외부 호출, 결과 저장 전 재검증, 중단된 ISSUING 정리 |
| 공급자 요청 | [HttpGeminiClient](../src/main/java/com/safecall/service/call/gemini/HttpGeminiClient.java) | 고정 발급 주소, AUDIO/voice/instruction 제약, 응답 상한·오류 처리 |
| 개인화 | [PromptComposer](../src/main/java/com/safecall/service/call/service/PromptComposer.java) | 확인 출처와 나이·호칭 조건, 개인정보 최소화, 게스트 중립 지침 |
| 키 수명 | [TransientKeys](../src/main/java/com/safecall/service/common/crypto/TransientKeys.java) | 토큰 사용/종료 커밋 후 폐기, 확정 롤백과 결과 불명 구분 |

로그아웃·세션 만료는 [AuthRepository](../src/main/java/com/safecall/service/auth/repository/AuthRepository.java), 동의 철회는 [UserTransactions](../src/main/java/com/safecall/service/user/service/UserTransactions.java)와 연결된다. 늦은 발급 결과가 종료·철회된 통화를 복구하지 않아야 한다.

## 프롬프트 발행

[개발 초안 SQL](../db/dev/seed-draft-call-prompts.sql)은 DRAFT 자료다. 실제 모델/API/음성 및 안전 지침을 검수한 뒤 [PromptPublicationService](../src/main/java/com/safecall/service/call/service/PromptPublicationService.java)로 발행한다. 4개 상황 × 3개 상대의 정확한 12개 조합을 검사하고, 이전 PUBLISHED는 RETIRED로 바꾸며 진행 중 통화는 기존 releaseId를 유지한다.

[PromptPublicationCommand](../src/main/java/com/safecall/service/call/service/PromptPublicationCommand.java)는 `app.prompts.publish-release-id`가 명시된 시작에서만 실행된다. 함께 전달하는 `app.prompts.validation-ref`에는 실제 검수 참조가 필요하다. 이 명령은 실행 시 DB를 변경하는 배포 작업이며 공개 HTTP API나 자동 seed가 아니다. 서비스 문서 발행은 별도의 검수된 데이터 적용 절차로 관리한다.

## 외부 계약과 검증 한계

공급자 계약의 참고 자료는 [Gemini 단기 토큰](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens), [공식 SDK의 token 변환](https://raw.githubusercontent.com/googleapis/js-genai/main/src/tokens.ts), [Live API](https://ai.google.dev/api/live)다. 모의 HTTP 테스트는 요청 형식과 서버 오류 처리를 확인하며 실제 모델이 제약·재개를 수락하고 강제한다는 증거는 아니다.

실제 세션 재개 handle은 브라우저 메모리에서 관리한다. 서버의 새 grant 발급은 재개 성공의 증명이 아니며, 성공 관측은 RESUMED로 기록한다. 수동 시나리오는 [통화 검증](ai-safety-call-swagger-test.md), 자동 결과는 [검증 문서](verification.md)를 참고한다.

# 백엔드 수정 후 재검토 — 2026-09-14

**후속 상태:** 아래 두 문제는 [추가 수정 보고서](backend-additional-fixes.md)의 변경으로 보완했다. 이 문서의 본문과 관측 결과는 수정 전 문제를 설명하는 기록이다.

판정: 추가 보완이 필요한 문제 2건을 확인했다. 이전 187개 테스트의 통과는 아래 경로의 정상 동작을 검증하지 않았다. 이번에는 서비스 구현을 변경하지 않았으며 관측용 테스트만 임시 추가한 뒤 원본 바이트로 복원했다.

## R1 · P2 · 재개 시 회원의 개인화 지침이 비회원 지침으로 바뀜

위치: `CallService.java:189`, `HttpGeminiClient.java:51-52`.

INITIAL은 회원의 카카오 성별·생년월일로 지침을 조립하지만 RESUME은 `composer.compose(p,null,now())`를 사용한다. 따라서 기존의 아들/딸 호칭 및 나이·성별 지침이 사라지고 성별 추정 호칭 금지와 guestInstruction이 들어간다. 이 새로운 systemInstruction은 토큰의 fieldMask에 포함되어 브라우저 setup에도 강제된다. 비기능 요구사항의 확인된 회원 정보 기반 호칭/대화 규칙과 충돌한다. 통화의 isDemographicApplied 플래그는 계속 true여서 현재 발급 지침과도 달라진다.

관측: 카카오에서 확인된 MALE/2000-01-01 회원의 초기 발급에는 `아들`, `26 남성`이 있었다. 같은 통화의 재개 발급 요청에는 두 항목이 없고 `성별을 추정하는 호칭을 쓰지 않는다`와 비회원 지침 `neutral`이 있었다. DB isDemographicApplied는 true였다. 지침 변경은 확인했지만 실제 Google 서비스에서 대화·호칭이 변하는지는 실연동으로 확인하지 않았다.

수정 방향: handle 보존과 안전 지침 잠금은 유지하되 최초 연결의 개인화 정책도 유지해야 한다. 재개용 지침을 비회원 정책으로 대체하지 말고, 최초 적용 정보를 어떻게 재사용할지 설계해야 한다. 전체 프롬프트 저장 금지와 개인정보 변경/철회 정책을 함께 만족해야 하므로 단순히 프롬프트를 DB에 저장하는 수정은 적절하지 않다.

공식 계약: https://ai.google.dev/api/generate-content#v1beta.AuthToken — fieldMask에 지정된 필드는 토큰의 setup으로 덮어쓴다.

## R2 · P2 · 키 생성 롤백 시 폐기 작업 기록 실패로 키가 유실 관리 상태가 됨

위치: `KeyDiscardQueue.java:31-33`, `TransientKeys.java:13-15`.

롤백한 신규 키는 discardRolledBackKey에서 먼저 별도 DB 트랜잭션으로 enqueue한다. 이 INSERT 또는 트랜잭션 시작이 실패하면 attempt까지 도달하지 못하며 외부 키 폐기도 호출하지 않는다. 원래 트랜잭션도 롤백했기 때문에 키 참조를 가진 서비스 행도 없다. DB가 복구되어도 worker가 찾는 keyDiscardJob에는 해당 키가 없다. DB 장애로 시작된 롤백에서 특히 문제가 될 수 있다.

관측: keyDiscardJob INSERT에 합성 SQL 오류를 발생시키고 키를 만든 트랜잭션을 롤백했다. keyDiscardJob은 0건, 외부 discard 호출은 0회였다. INSERT 오류를 제거하고 worker를 실행해도 키 파일이 남았다. 이 시험은 큐 기록 실패 경로를 검증했으며 실제 DB 프로세스를 중단하지는 않았다.

수정 방향: 큐 기록 실패가 직접 폐기 시도까지 막지 않도록 하면서, 둘 다 실패하거나 프로세스가 종료되어도 신규 키를 대조·복구할 수 있는 영속적인 생성/정리 이력을 마련해야 한다. DB 커밋 후 기존 키를 폐기하는 outbox 경로는 이번 문제와 구분한다.

## 검증

격리 MySQL 및 모의 외부 API로 기존 단위 51개·통합 136개와 관측 테스트 2개를 실행했다. 합계 189개 실행 성공이며, **187개 회귀 통과 + 문제 동작 관측 2개 성공**이라는 뜻이다. 문제 2건이 수정되었다는 뜻은 아니다.

관측 코드: `build/second-review/probes.java.txt`. 결과: `build/second-review/results.xml`. 원본 테스트 백업: `build/second-review/WebIntegrationTest.original`. 관측 suite 종료 후 테스트 소스를 백업과 SHA-256이 같도록 복원했다. build 산출물은 Git 제외 대상이다. 기존 verification-backend-review.json은 이전 187개 회귀 기록으로 유지했다.

이번 대조 범위에서 F1·F4·F5·F6 수정에 대한 추가 결함은 발견하지 못했다. 기존 DB용 마이그레이션, 운영 외부 키 저장소 구현, 실제 Gemini 재개 검수는 여전히 별도의 배포 조건이다. 이번에는 실제 사용자 데이터·서비스 DB·Google/Kakao 외부 credential을 사용하지 않았다.

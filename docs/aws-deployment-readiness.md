# AWS 배포 준비 및 환경 설정

현재 단계는 로컬·운영 설정과 사용자 키 저장소의 분리다. AWS 리소스 생성, KMS 연동, 운영 배포까지 완료된 상태는 아니다. **외부 사용자 키 저장소가 구현되기 전에는 `prod` 시작을 의도적으로 차단한다.**

## 환경 선택

| 항목 | `local` | `prod` |
| --- | --- | --- |
| 선택 | 프로필 미지정 시 기본값 | 배포 환경에서 `SPRING_PROFILES_ACTIVE=prod` 명시 |
| 설정 파일 | 공통 `application.yml` + `application-local.yml` | 공통 `application.yml` + `application-prod.yml` |
| `.env` 자동 읽기 | 사용 | 사용하지 않음 |
| 사용자 키 | `FileUserKeyStore`, `CRYPTO_KEY_DIRECTORY` | 외부 `UserKeyStore` 구현 필요 |
| Swagger | 기본 활성화, `SWAGGER_ENABLED=false`로 해제 | 기본 비활성화 |
| MySQL | 로컬 개발 기본값 제공 | 호스트·계정·비밀번호 필수, TLS `VERIFY_IDENTITY` |

`local`과 `prod`를 함께 활성화하면 시작에 실패한다. 운영 프로필 선택은 로컬 `.env` 안에 넣지 말고 EC2 서비스 또는 ECS 작업 정의 등의 실행 환경에서 명시한다. 로컬 키 파일이 없어도 새 키로 기존 암호문을 복구할 수는 없다. 키가 없는 기존 사용자를 읽을 때 자동 재생성하지 않는다.

기존 로컬 명령은 그대로 사용한다.

```powershell
./gradlew.bat bootRun
```

운영 프로필의 차단 여부만 확인하려면 다음을 사용한다. 현재는 외부 키 저장소 미구현 오류로 종료되는 것이 정상이다.

```powershell
./gradlew.bat bootRun --args="--spring.profiles.active=prod"
```

## 2장 구현에서 지킬 경계

- 프로필·연락망 서비스는 `UserKeyStore`와 `SecretCrypto`를 사용한다. `.keys` 파일을 직접 읽거나 쓰지 않는다.
- 사용자 키 참조는 DB에 저장하되 키 원문은 DB의 개인정보 테이블에 저장하지 않는다.
- 키 생성 실패, DB 롤백, 키 저장소 장애의 응답에서 키·토큰·개인정보 원문을 노출하지 않는다.
- 탈퇴 시 키를 폐기하고 DB 백업 복원으로 폐기한 키가 되살아나지 않도록 설계한다.

## 첫 AWS 배포 전 완료할 작업

1. **외부 키 저장소 구현:** KMS로 사용자별 데이터 키를 보호하고 암호화된 키를 별도 영구 저장소에 보관한다. 모든 서버가 같은 `keyRef`를 해석해야 한다. 현재 `UserKeyStore.read` 계약은 복호화된 32바이트 데이터 키를 반환하므로 KMS 마스터 키 자체를 반환해서는 안 된다. 재시작·서버 교체·동시 가입·키 생성 롤백·키 폐기 시험을 수행한다.
2. **비밀값 주입:** DB 비밀번호, 카카오 Secret, JWT/HMAC/응답 암호화 키는 Secrets Manager 등에서 IAM 역할로 접근하도록 연결한다. 현재 코드는 Secrets Manager를 직접 호출하지 않으므로 배포 계층의 주입 또는 별도 연동을 구현해야 한다. 기존 데이터가 있으면 키를 임의 교체하지 않고 버전·재암호화 절차를 마련한다.
3. **RDS MySQL:** 사설 네트워크와 전용 계정을 사용하고 RDS 인증서를 신뢰 저장소에 구성해 TLS 호스트 검증을 통과시킨다. 앱은 DML만, 스키마 변경은 별도 계정으로 수행한다. `schema-mysql.sql`은 신규 DB용이며 기존 운영 DB에 반복 실행하는 마이그레이션이 아니다.
4. **ALB와 HTTPS:** ALB 등 신뢰하는 인입 계층에서 HTTPS를 종료하고 서버 직접 접근을 제한한다. 현재 `forward-headers-strategy: none`과 `getRemoteAddr()` 기반 요청 제한은 로컬용이다. 실제 배포 프록시 구성을 확정한 뒤 신뢰 범위와 클라이언트 IP 추출을 함께 수정한다. 전달 헤더를 무조건 신뢰하지 않는다.
5. **운영 상태와 복구:** 제한된 상태 확인 경로, 종료 시 요청 처리, DB·키 백업/복구, 만료 작업의 다중 서버 실행 검증을 준비한다. 사용자별 키 폐기가 보관된 암호화 키 사본과 백업에도 적용되는지 확인한다.
6. **Linux DB 검증:** Windows MySQL의 `lower_case_table_names=1`에서는 `appUser`가 `appuser`로 저장될 수 있다. Linux/RDS의 실제 설정을 확인하고 DDL·쿼리·데이터 이전의 테이블 이름을 일치시킨다. 빈 운영 DB에는 저장소의 DDL을 적용하고 Linux 환경에서 통합 테스트를 실행한다.
7. **외부 로그인:** 현재 A02는 클라이언트가 받은 카카오 access token을 검증한다. 웹 OAuth callback은 미구현이다. 배포만으로 callback이 생기지 않으며, 웹 로그인을 도입하는 경우 HTTPS callback 구현과 카카오 콘솔 등록을 함께 진행한다.

로컬 테스트 DB를 운영으로 이전하지 않는다면 운영은 새 DB와 별도 키로 시작한다. 기존 암호화 데이터를 이전한다면 대응 키와 HMAC 식별값의 연속성도 함께 보장해야 한다.

## 참고

- [AWS KMS 데이터 키](https://docs.aws.amazon.com/kms/latest/developerguide/data-keys.html)
- [AWS Secrets Manager 권장 사항](https://docs.aws.amazon.com/secretsmanager/latest/userguide/best-practices.html)
- [RDS MySQL TLS 인증서](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/ssl-certificate-rotation-mysql.html)
- [ALB 전달 헤더](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html)
- [MySQL 테이블 이름 대소문자](https://dev.mysql.com/doc/refman/8.0/en/identifier-case-sensitivity.html)

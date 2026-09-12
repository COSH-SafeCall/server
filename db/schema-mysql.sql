-- SafeCall 물리 DB 스키마 v4.2-web-mvp / 2026-09-12
-- design의 프로젝트 개요·비기능 요구사항·기능 요구사항을 기준으로 한 웹 MVP MySQL DDL.
-- MySQL 8.0.41 이상 8.0/8.4, InnoDB, utf8mb4_0900_as_cs.
-- 빈 DB 신규 설치 DDL. 기존 DB에 적용하는 ALTER/이관 스크립트가 아니다.
-- CREATE DATABASE 실패 시 중단. mysql --force 금지. DDL은 암묵적 COMMIT.
-- UUID BINARY(16), UUID_TO_BIN(value,0)/BIN_TO_UUID(value,0), UTC DATETIME(6).
-- camelCase 식별자는 백틱 인용. Boolean은 is 접두사와 0/1 CHECK.
-- Cipher는 애플리케이션 인증 암호화, Hash는 용도 분리 HMAC-SHA-256, keyRef는 외부 키 참조.
-- 브라우저 React SPA: 보안 쿠키, Gemini 직접 WSS, 웹 내부 메시지 작성, SOS 안내만.
-- 원음/대화/완성 프롬프트/좌표/위치 URL/메시지 본문/세션 재개 핸들 영속 저장 없음.
-- 세션 쿠키·CSRF 원문은 저장하지 않는다. 단기 Gemini 토큰만 짧은 재조회용 암호문 허용.
-- API_명세_최종.md, DB_논리_설계_최종.md, 변경_대응표_최종.md와 함께 적용한다.

CREATE DATABASE `safecall` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
USE `safecall`;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_cs;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,ONLY_FULL_GROUP_BY,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

CREATE TABLE `appUser` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`kakaoSubjectHash` VARBINARY(32) NOT NULL COMMENT '카카오 회원번호의 용도 분리 HMAC-SHA-256; 계정 연결/중복 방지',
	`kakaoSubjectCipher` BLOB NOT NULL COMMENT '카카오 회원번호 암호문; 탈퇴 시 연결 해제에 필요한 최소 정보',
	`keyRef` TEXT NOT NULL COMMENT 'DB 외부 암호화 키의 참조값(키 원문 아님)',
	`status` VARCHAR(24) NOT NULL DEFAULT 'ONBOARDING' COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`nameCipher` BLOB  COMMENT '사용자 또는 보호자 이름의 인증 암호화 봉투',
	`genderCipher` BLOB  COMMENT 'MALE/FEMALE 성별 암호문; 미확인 시 NULL',
	`birthDateCipher` BLOB  COMMENT '확인된 양력 생년월일 암호문; 미확인 시 NULL',
	`phoneCipher` BLOB  COMMENT '정규화한 010 휴대폰 번호 암호문; 원문 로그 금지',
	`phoneHash` VARBINARY(32)  COMMENT '소유자 범위를 포함한 정규화 번호 HMAC-SHA-256; 동일 번호 판별용',
	`genderSource` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT '성별 출처 KAKAO/USER_CONFIRMED/UNKNOWN; 클라이언트 임의 설정 금지',
	`birthDateSource` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT '생년월일 출처 KAKAO/USER_CONFIRMED/UNKNOWN',
	`profileConfirmedAt` DATETIME(6)  COMMENT '회원이 기본 정보를 확인한 시각',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`updatedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '최종 수정 시각; 서비스 UPDATE에서 명시적으로 갱신',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전; 변경 성공 시 1 증가',
	PRIMARY KEY(`id`),
	CONSTRAINT `ckAppUser1` CHECK (octet_length(`kakaoSubjectHash`)=32),
	CONSTRAINT `uqAppUser1` UNIQUE(`kakaoSubjectHash`),
	CONSTRAINT `ckAppUser2` CHECK (`status` IN ('ONBOARDING','ACTIVE','DELETION_PENDING')),
	CONSTRAINT `ckAppUser3` CHECK (`phoneHash` IS NULL OR octet_length(`phoneHash`)=32),
	CONSTRAINT `ckAppUser4` CHECK (`genderSource` IN ('KAKAO','USER_CONFIRMED','UNKNOWN')),
	CONSTRAINT `ckAppUser5` CHECK (`birthDateSource` IN ('KAKAO','USER_CONFIRMED','UNKNOWN')),
	CONSTRAINT `ckAppUser6` CHECK (`version`>0),
	CONSTRAINT `ckAppUser7` CHECK ((`phoneCipher` IS NULL) = (`phoneHash` IS NULL)),
	CONSTRAINT `ckAppUser8` CHECK ((`genderCipher` IS NULL) = (`genderSource`='UNKNOWN')),
	CONSTRAINT `ckAppUser9` CHECK ((`birthDateCipher` IS NULL) = (`birthDateSource`='UNKNOWN'))
) ENGINE=InnoDB COMMENT='사용자: 카카오 subject 전역 유일. 계정은 ONBOARDING/ACTIVE/DELETION_PENDING. 프로필 미확인 항목은 null, UNKNOWN 출처와 동치';

CREATE TABLE `webSession` (
	`id` BINARY(16) NOT NULL COMMENT '서버 UUID; 쿠키 원문과 다른 내부 식별자',
	`sessionHash` VARBINARY(32) NOT NULL COMMENT '256비트 난수 세션 쿠키의 용도 분리 HMAC; 원문 미저장',
	`csrfHash` VARBINARY(32) NOT NULL COMMENT '세션에 묶인 안정적인 CSRF 토큰 HMAC; 인증 세션 교체 시 함께 교체',
	`sensitiveVerifiedAt` DATETIME(6) COMMENT '동일 Kakao subject의 명시적 재인증 성공 시각; 일반 로그인은 NULL',
	`userId` BINARY(16) COMMENT 'KAKAO 세션만 회원 참조',
	`kind` VARCHAR(9) NOT NULL COMMENT 'ANONYMOUS/GUEST/KAKAO; 익명은 CSRF 및 OAuth 준비 전용',
	`onboardingStep` VARCHAR(24) NOT NULL COMMENT '현재 화면 단계; 브라우저 권한 허용의 증거가 아님',
	`status` VARCHAR(7) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED/EXPIRED',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 생성 시각',
	`expiresAt` DATETIME(6) NOT NULL COMMENT '고정 만료; 게스트 전환은 최대 24시간',
	`revokedAt` DATETIME(6) COMMENT 'REVOKED일 때만 존재',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '온보딩 변경 낙관적 잠금',
	PRIMARY KEY (`id`),
	CONSTRAINT `uqWebSessionHash` UNIQUE (`sessionHash`),
	CONSTRAINT `fkWebSessionUser` FOREIGN KEY (`userId`) REFERENCES `appUser` (`id`),
	CONSTRAINT `ckWebSessionHash` CHECK (octet_length(`sessionHash`)=32 AND octet_length(`csrfHash`)=32),
	CONSTRAINT `ckWebSessionKind` CHECK (`kind` IN ('ANONYMOUS','GUEST','KAKAO')),
	CONSTRAINT `ckWebSessionOwner` CHECK ((`kind`='KAKAO')=(`userId` IS NOT NULL)),
	CONSTRAINT `ckWebSessionStatus` CHECK (`status` IN ('ACTIVE','REVOKED','EXPIRED')),
	CONSTRAINT `ckWebSessionRevoked` CHECK ((`status`='REVOKED')=(`revokedAt` IS NOT NULL)),
	CONSTRAINT `ckWebSessionStep` CHECK (`onboardingStep` IN ('ENTRY','PROFILE','CONTACTS','CONSENTS','PERMISSIONS','SOS_GUIDE','MESSAGE_TEST','COMPLETE')),
	CONSTRAINT `ckWebSessionGuestStep` CHECK (`kind`<>'GUEST' OR `onboardingStep` IN ('PERMISSIONS','SOS_GUIDE','COMPLETE')),
	CONSTRAINT `ckWebSessionAnonymousStep` CHECK ((`kind`='ANONYMOUS')=(`onboardingStep`='ENTRY')),
	CONSTRAINT `ckWebSessionLifetime` CHECK (`expiresAt`>`createdAt`),
	CONSTRAINT `ckWebSessionGuestLifetime` CHECK (`kind`<>'GUEST' OR `expiresAt`<=DATE_ADD(`createdAt`, INTERVAL 24 HOUR)),
	CONSTRAINT `ckWebSessionVersion` CHECK (`version`>0),
	CONSTRAINT `ckWebSensitiveOwner` CHECK (`sensitiveVerifiedAt` IS NULL OR `kind`='KAKAO'),
	CONSTRAINT `ckWebSensitiveTime` CHECK (`sensitiveVerifiedAt` IS NULL OR (`sensitiveVerifiedAt`>=`createdAt` AND `sensitiveVerifiedAt`<`expiresAt`)),
	INDEX `ixWebSessionUser` (`userId`),
	INDEX `ixWebSessionExpiry` (`status`,`expiresAt`)
) ENGINE=InnoDB COMMENT='웹 세션: HttpOnly 보안 쿠키의 해시만 저장. 설치 ID·지문·access/refresh 토큰 없음';

CREATE TABLE `oauthAttempt` (
	`id` BINARY(16) NOT NULL COMMENT 'OAuth 시도 UUID',
	`sessionId` BINARY(16) NOT NULL COMMENT 'OAuth를 시작한 브라우저 세션',
	`stateHash` VARBINARY(32) NOT NULL COMMENT 'OAuth state 난수 HMAC; 브라우저 쿠키와 함께 검증',
	`purpose` VARCHAR(6) NOT NULL DEFAULT 'LOGIN' COMMENT 'LOGIN 또는 REAUTH; 재인증은 기존 회원 subject 일치 필수',
	`status` VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EXCHANGING/SUCCEEDED/FAILED/EXPIRED',
	`redirectUri` VARCHAR(512) NOT NULL COMMENT '서버 허용목록의 정확한 콜백 URI; 사용자 임의 입력 금지',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 시작 시각',
	`expiresAt` DATETIME(6) NOT NULL COMMENT 'state 유효 기한; 기본 10분',
	`completedAt` DATETIME(6) COMMENT '성공·실패·만료 확정 시각',
	PRIMARY KEY (`id`),
	CONSTRAINT `fkOauthAttemptSession` FOREIGN KEY (`sessionId`) REFERENCES `webSession` (`id`) ON DELETE CASCADE,
	CONSTRAINT `uqOauthAttemptState` UNIQUE (`stateHash`),
	CONSTRAINT `ckOauthPurpose` CHECK (`purpose` IN ('LOGIN','REAUTH')),
	CONSTRAINT `ckOauthAttemptHash` CHECK (octet_length(`stateHash`)=32),
	CONSTRAINT `ckOauthAttemptStatus` CHECK (`status` IN ('PENDING','EXCHANGING','SUCCEEDED','FAILED','EXPIRED')),
	CONSTRAINT `ckOauthAttemptTime` CHECK (`expiresAt`>`createdAt` AND (`completedAt` IS NULL OR `completedAt`>=`createdAt`)),
	CONSTRAINT `ckOauthAttemptDone` CHECK ((`status` IN ('SUCCEEDED','FAILED','EXPIRED'))=(`completedAt` IS NOT NULL)),
	INDEX `ixOauthAttemptExpiry` (`status`,`expiresAt`)
) ENGINE=InnoDB COMMENT='OAuth 웹 리다이렉트 일회성 state: 인증 코드·Kakao access token 원문 저장 없음';

CREATE TABLE `userSetting` (
	`userId` BINARY(16) NOT NULL COMMENT '소유 회원 식별자',
	`incomingAlertMode` VARCHAR(8) NOT NULL DEFAULT 'RINGTONE' COMMENT '가상 수신 알림: RINGTONE/SILENT; 브라우저 자동 재생 제한 적용',
	`updatedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '최종 수정 시각; 서비스 UPDATE에서 명시적으로 갱신',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전; 변경 성공 시 1 증가',
	CONSTRAINT `fkUserSetting1` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`) ON DELETE CASCADE,
	PRIMARY KEY(`userId`),
	CONSTRAINT `ckUserSetting1` CHECK (`incomingAlertMode` IN ('RINGTONE','SILENT')),
	CONSTRAINT `ckUserSetting2` CHECK (`version`>0)
) ENGINE=InnoDB COMMENT='사용자 설정: 회원별 1개; RINGTONE 기본, SILENT 허용. 브라우저 권한을 설정 칼럼으로 저장하지 않음';

CREATE TABLE `serviceDocument` (
	`code` VARCHAR(24) NOT NULL COMMENT '문서/상황/상대 또는 허용된 운영 이벤트 코드; 해당 CHECK 및 API 허용목록 참조',
	`version` INT NOT NULL COMMENT '문서 버전; code와 복합 기본키, 발행 후 불변',
	`title` TEXT NOT NULL COMMENT '문서 제목',
	`body` TEXT NOT NULL COMMENT '개인정보가 없는 동의/안내 문서 본문',
	`isConsent` TINYINT(1) NOT NULL COMMENT '동의 대상 문서 여부',
	`isRequired` TINYINT(1) NOT NULL DEFAULT false COMMENT '필수 동의 여부; 안내 문서는 false',
	`isCurrent` TINYINT(1) NOT NULL DEFAULT false COMMENT '현재 사용 중인 문서 버전 여부',
	`publishedAt` DATETIME(6) NOT NULL COMMENT '콘텐츠 발행 시각',
	`currentMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `isCurrent` THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	CONSTRAINT `ckServiceDocument1` CHECK (`code` IN ('PRIVACY_PROCESSING','AI_CALL','LOCATION_PROCESSING', 'PRIVACY_NOTICE','AI_POLICY','HELP','SOS_GUIDE','PRE_CALL_NOTICE')),
	CONSTRAINT `ckServiceDocument2` CHECK (`version`>0),
	CONSTRAINT `ckServiceDocument3` CHECK (`isConsent` IN (0,1)),
	CONSTRAINT `ckServiceDocument4` CHECK (`isRequired` IN (0,1)),
	CONSTRAINT `ckServiceDocument5` CHECK (`isCurrent` IN (0,1)),
	PRIMARY KEY(`code`,`version`),
	CONSTRAINT `ckServiceDocument6` CHECK (NOT `isRequired` OR `isConsent`),
	CONSTRAINT `ckServiceDocument7` CHECK (`isConsent` = (`code` IN ('PRIVACY_PROCESSING','AI_CALL','LOCATION_PROCESSING'))),
	CONSTRAINT `uqServiceDocument1` UNIQUE(`code`,`currentMarker`)
) ENGINE=InnoDB COMMENT='서비스 문서 버전: 코드별 current 최대1. 동의 코드와 안내 코드를 구분. 발행된 문서 내용은 불변';

CREATE TABLE `oauthConsent` (
	`attemptId` BINARY(16) NOT NULL COMMENT '사용자가 서비스 동의 후 시작한 OAuth 시도',
	`documentCode` VARCHAR(24) NOT NULL COMMENT '서비스 동의 문서 코드; 안내 문서는 금지',
	`documentVersion` INT NOT NULL COMMENT '사용자가 읽고 결정한 정확한 문서 버전',
	`action` VARCHAR(8) NOT NULL COMMENT 'GRANTED/DECLINED; 회원 연계 전 선택 기록',
	`recordedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버 접수 시각; 프로필/연락처 값은 저장하지 않음',
	PRIMARY KEY (`attemptId`,`documentCode`),
	CONSTRAINT `fkOauthConsentAttempt` FOREIGN KEY (`attemptId`) REFERENCES `oauthAttempt` (`id`) ON DELETE CASCADE,
	CONSTRAINT `fkOauthConsentDocument` FOREIGN KEY (`documentCode`,`documentVersion`) REFERENCES `serviceDocument` (`code`,`version`),
	CONSTRAINT `ckOauthConsentCode` CHECK (`documentCode` IN ('PRIVACY_PROCESSING','AI_CALL','LOCATION_PROCESSING')),
	CONSTRAINT `ckOauthConsentAction` CHECK (`action` IN ('GRANTED','DECLINED')),
	CONSTRAINT `ckOauthConsentRequired` CHECK (`documentCode`='LOCATION_PROCESSING' OR `action`='GRANTED')
) ENGINE=InnoDB COMMENT='회원 프로필 수집 이전 서비스 동의 스냅샷. 로그인 성공 트랜잭션에서 consentEvent로 연계';

CREATE TABLE `consentEvent` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`userId` BINARY(16) NOT NULL COMMENT '소유 회원 식별자',
	`documentCode` VARCHAR(24) NOT NULL COMMENT '동의한 서비스 문서 코드',
	`documentVersion` INT NOT NULL COMMENT '동의 대상 문서의 정확한 버전',
	`action` VARCHAR(9) NOT NULL COMMENT 'GRANTED/DECLINED/WITHDRAWN 동의 사건',
	`recordedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버가 사건을 기록한 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkConsentEvent1` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckConsentEvent1` CHECK (`action` IN ('GRANTED','DECLINED','WITHDRAWN')),
	CONSTRAINT `fkConsentEvent2` FOREIGN KEY(`documentCode`,`documentVersion`) REFERENCES `serviceDocument`(`code`,`version`),
	INDEX `ixConsentLatest` (`userId`,`documentCode`,`recordedAt` DESC,`id` DESC),
	CONSTRAINT `ckConsentDocumentCode` CHECK (`documentCode` IN ('PRIVACY_PROCESSING','AI_CALL','LOCATION_PROCESSING'))
) ENGINE=InnoDB COMMENT='동의 사건: 회원/문서 버전 FK. 최신 이벤트와 현재 버전으로 효력을 계산. 동의 누락을 자동 동의로 만들지 않음';

CREATE TABLE `emergencyContact` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`userId` BINARY(16) NOT NULL COMMENT '소유 회원 식별자',
	`slot` SMALLINT NOT NULL COMMENT '보호자 슬롯 1 또는 2; 회원별 유일하여 최대 2명 보장',
	`nameCipher` BLOB NOT NULL COMMENT '사용자 또는 보호자 이름의 인증 암호화 봉투',
	`relationshipCipher` BLOB NOT NULL COMMENT '보호자 관계 설명의 암호문; AI 통화 상대 enum과 별개',
	`phoneCipher` BLOB NOT NULL COMMENT '정규화한 010 휴대폰 번호 암호문; 원문 로그 금지',
	`phoneHash` VARBINARY(32) NOT NULL COMMENT '소유자 범위를 포함한 정규화 번호 HMAC-SHA-256; 동일 번호 판별용',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`updatedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '최종 수정 시각; 서비스 UPDATE에서 명시적으로 갱신',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전; 변경 성공 시 1 증가',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkEmergencyContact1` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckEmergencyContact1` CHECK (`slot` IN (1,2)),
	CONSTRAINT `ckEmergencyContact2` CHECK (octet_length(`phoneHash`)=32),
	CONSTRAINT `ckEmergencyContact3` CHECK (`version`>0),
	CONSTRAINT `uqEmergencyContact1` UNIQUE(`userId`,`slot`),
	CONSTRAINT `uqEmergencyContact2` UNIQUE(`userId`,`phoneHash`),
	CONSTRAINT `uqEmergencyContact3` UNIQUE(`id`,`userId`)
) ENGINE=InnoDB COMMENT='비상 연락처: slot 1/2 및 회원+slot UNIQUE로 최대2. 동일 회원 번호 중복/본인 번호 거절. 본인은 별도 연락처 아님';

CREATE TABLE `scenario` (
	`code` VARCHAR(24) NOT NULL COMMENT '문서/상황/상대 또는 허용된 운영 이벤트 코드; 해당 CHECK 및 API 허용목록 참조',
	`label` TEXT NOT NULL COMMENT '원문 요구사항의 화면 표시 라벨',
	`sortOrder` SMALLINT NOT NULL COMMENT '고정 선택지 표시 순서',
	CONSTRAINT `ckScenario1` CHECK (`code` IN ('FOLLOWED','UNSAFE_TAXI','STRANGER_NEARBY','WALKING_ALONE')),
	PRIMARY KEY(`code`),
	CONSTRAINT `ckScenario2` CHECK (`sortOrder` BETWEEN 1 AND 4),
	CONSTRAINT `uqScenario1` UNIQUE(`sortOrder`)
) ENGINE=InnoDB COMMENT='상황: FOLLOWED/UNSAFE_TAXI/STRANGER_NEARBY/WALKING_ALONE 정확히4. 기타/자유 입력 없음';

CREATE TABLE `counterpart` (
	`code` VARCHAR(6) NOT NULL COMMENT '문서/상황/상대 또는 허용된 운영 이벤트 코드; 해당 CHECK 및 API 허용목록 참조',
	`label` TEXT NOT NULL COMMENT '원문 요구사항의 화면 표시 라벨',
	`displayName` TEXT NOT NULL COMMENT '서버가 지정한 가상 발신자 표시 이름; 사용자 입력 금지',
	`sortOrder` SMALLINT NOT NULL COMMENT '고정 선택지 표시 순서',
	CONSTRAINT `ckCounterpart1` CHECK (`code` IN ('FATHER','MOTHER','FRIEND')),
	PRIMARY KEY(`code`),
	CONSTRAINT `ckCounterpart2` CHECK (`sortOrder` BETWEEN 1 AND 3),
	CONSTRAINT `uqCounterpart1` UNIQUE(`sortOrder`)
) ENGINE=InnoDB COMMENT='통화 상대: FATHER/MOTHER/FRIEND 정확히3. 표시명은 서버 지정, 초기값 아빠/엄마/친구';

CREATE TABLE `promptRelease` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`version` INT NOT NULL COMMENT '프롬프트 배포 버전; 전역 유일',
	`safetyInstruction` TEXT NOT NULL COMMENT '개인정보 없는 공통 AI 안전 지침',
	`demographicRules` TEXT NOT NULL COMMENT '확인된 나이·성별의 조건부 추가 지침 규칙',
	`guestInstruction` TEXT NOT NULL COMMENT '게스트/미확인 인구통계 정보에 대한 중립 대화 지침',
	`modelId` TEXT NOT NULL COMMENT '검수된 Gemini 모델 식별자',
	`apiVersion` TEXT NOT NULL COMMENT '해당 모델 연결의 검수된 API 버전',
	`status` VARCHAR(9) NOT NULL DEFAULT 'DRAFT' COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`validationRef` TEXT  COMMENT '12페르소나 대화 품질·안전성 검증 결과 참조; 개인정보 금지',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`publishedAt` DATETIME(6)  COMMENT '콘텐츠 발행 시각',
	`publishedMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `status`='PUBLISHED' THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	PRIMARY KEY(`id`),
	CONSTRAINT `ckPromptRelease1` CHECK (`version`>0),
	CONSTRAINT `uqPromptRelease1` UNIQUE(`version`),
	CONSTRAINT `ckPromptRelease2` CHECK (`status` IN ('DRAFT','PUBLISHED','RETIRED')),
	CONSTRAINT `ckPromptRelease3` CHECK (`status`='DRAFT' OR (`publishedAt` IS NOT NULL AND `validationRef` IS NOT NULL)),
	CONSTRAINT `uqPromptRelease2` UNIQUE(`publishedMarker`)
) ENGINE=InnoDB COMMENT='프롬프트 배포 버전: DRAFT/PUBLISHED/RETIRED. PUBLISHED 최대1. 발행 전 12페르소나+안전성 검증 필수';

CREATE TABLE `personaPrompt` (
	`releaseId` BINARY(16) NOT NULL COMMENT '통화에 적용한 프롬프트 배포 버전 식별자',
	`scenarioCode` VARCHAR(24) NOT NULL COMMENT '4개 고정 상황 중 선택한 코드',
	`counterpartCode` VARCHAR(6) NOT NULL COMMENT 'FATHER/MOTHER/FRIEND 중 선택한 코드',
	`baseInstruction` TEXT NOT NULL COMMENT '상황×상대 조합의 기본 프롬프트; 개인별 생성 프롬프트 아님',
	`voiceId` TEXT NOT NULL COMMENT 'Gemini 음성 설정 ID; 음성 파일/녹음 데이터 아님',
	CONSTRAINT `fkPersonaPrompt1` FOREIGN KEY(`releaseId`) REFERENCES `promptRelease`(`id`),
	CONSTRAINT `fkPersonaPrompt2` FOREIGN KEY(`scenarioCode`) REFERENCES `scenario`(`code`),
	CONSTRAINT `fkPersonaPrompt3` FOREIGN KEY(`counterpartCode`) REFERENCES `counterpart`(`code`),
	PRIMARY KEY(`releaseId`,`scenarioCode`,`counterpartCode`)
) ENGINE=InnoDB COMMENT='페르소나 프롬프트: release마다 4×3=12행. 각 조합 유일. release 발행 후 수정 금지';

CREATE TABLE `callSession` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`sessionId` BINARY(16) NOT NULL COMMENT '소유 웹 세션 식별자',
	`pageKeyHash` VARBINARY(32) NOT NULL COMMENT '현재 페이지 메모리 전용 소유 키 HMAC; 새로고침 후 재개 차단',
	`clientCallId` BINARY(16) NOT NULL COMMENT '사용자 시작 행동 UUID; 동일 세션 내 중복 생성 방지',
	`startMode` VARCHAR(8) NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD/QUICK; QUICK은 FATHER 고정',
	`releaseId` BINARY(16) NOT NULL COMMENT '통화에 적용한 프롬프트 배포 버전 식별자',
	`scenarioCode` VARCHAR(24) NOT NULL COMMENT '4개 고정 상황 중 선택한 코드',
	`counterpartCode` VARCHAR(6) NOT NULL COMMENT 'FATHER/MOTHER/FRIEND 중 선택한 코드',
	`state` VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT '처리 상태; 허용값과 종료 조건은 아래 CHECK 참조',
	`endReason` VARCHAR(32)  COMMENT '통화 종료/실패 사유; 종료 시각과 함께 존재',
	`isDemographicApplied` TINYINT(1) NOT NULL COMMENT 'KAKAO 출처 나이·성별 톤 지침 적용 여부; 실제 나이 값 미저장',
	`isGenderAddressApplied` TINYINT(1) NOT NULL COMMENT 'KAKAO 성별에 따른 부모 호칭 적용 여부; 게스트는 false',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`ringingAt` DATETIME(6)  COMMENT '가상 수신 화면 표시 시각',
	`answeredAt` DATETIME(6)  COMMENT '사용자 받기 처리 시각',
	`endedAt` DATETIME(6)  COMMENT '통화 종료 시각',
	`lastHeartbeatAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버가 마지막 heartbeat를 수락한 시각',
	`leaseExpiresAt` DATETIME(6) NOT NULL COMMENT '마지막 유효 heartbeat 기준 잔여 세션 정리 기한',
	`expiresAt` DATETIME(6) NOT NULL COMMENT '생성 시 확정한 최대 통화 종료 기한; 기본 600초/세션/모델 한도 중 최솟값',
	`policyVersion` VARCHAR(32) NOT NULL DEFAULT 'mvp-2026-09-11' COMMENT '통화 생성 시 적용한 공개 운영 정책 버전',
	`maxResumeAttempts` INT NOT NULL DEFAULT 1 COMMENT '통화 전체 재개 시도 예산; 발급 요청 수락 시 소비하며 성공해도 복원하지 않음',
	`resumeDelayMs` INT NOT NULL DEFAULT 1000 COMMENT '자동 재개 전 대기 밀리초; GoAway는 남은 기한 안에서 조정',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전; 변경 성공 시 1 증가',
	`activeMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `state` IN ('CREATED','PREPARING','RINGING','ACTIVE') THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkCallSession1` FOREIGN KEY(`sessionId`) REFERENCES `webSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckCallSession1` CHECK (`state` IN ('CREATED','PREPARING','RINGING','ACTIVE','ENDED','FAILED')),
	CONSTRAINT `ckCallSession2` CHECK (`endReason` IN ('USER_ENDED','DECLINED','BACK_NAVIGATION','TAB_HIDDEN','PAGE_EXIT','PAGE_RELOAD','SWITCH_TO_FALLBACK','CONNECTION_FAILED','RINGING_FAILED', 'MICROPHONE_FAILED','AUDIO_FAILED','CONNECTION_LOST','RESUMPTION_FAILED','SESSION_EXPIRED','DURATION_LIMIT', 'LOGOUT','CONSENT_WITHDRAWN','DATA_DELETION')),
	CONSTRAINT `ckCallSession3` CHECK (`isDemographicApplied` IN (0,1)),
	CONSTRAINT `ckCallSession4` CHECK (`isGenderAddressApplied` IN (0,1)),
	CONSTRAINT `ckCallSession5` CHECK (`version`>0),
	CONSTRAINT `fkCallSession2` FOREIGN KEY(`releaseId`,`scenarioCode`,`counterpartCode`) REFERENCES `personaPrompt`(`releaseId`,`scenarioCode`,`counterpartCode`),
	CONSTRAINT `uqCallSession1` UNIQUE(`id`,`sessionId`),
	CONSTRAINT `ckCallSession6` CHECK ((`state` IN ('ENDED','FAILED')) = (`endedAt` IS NOT NULL)),
	CONSTRAINT `ckCallSession7` CHECK ((`state` IN ('ENDED','FAILED')) = (`endReason` IS NOT NULL)),
	CONSTRAINT `ckCallSession8` CHECK (`endedAt` IS NULL OR `endedAt`>=`createdAt`),
	CONSTRAINT `ckCallSession9` CHECK (`answeredAt` IS NULL OR (`ringingAt` IS NOT NULL AND `answeredAt`>=`ringingAt`)),
	CONSTRAINT `ckCallSession10` CHECK (`state`<>'ACTIVE' OR `answeredAt` IS NOT NULL),
	CONSTRAINT `ckCallSession11` CHECK (`state`<>'RINGING' OR `ringingAt` IS NOT NULL),
	CONSTRAINT `ckCallSession12` CHECK (`expiresAt`>`createdAt`),
	CONSTRAINT `uqCallSession2` UNIQUE(`sessionId`,`activeMarker`),
	INDEX `ixCallHistory` (`sessionId`,`createdAt` DESC,`id` DESC),
	INDEX `ixCallReaper` (`state`,`lastHeartbeatAt`),
	CONSTRAINT `uqCallClientId` UNIQUE (`sessionId`,`clientCallId`),
	CONSTRAINT `ckCallStartMode` CHECK (`startMode` IN ('STANDARD','QUICK')),
	CONSTRAINT `ckQuickFather` CHECK (`startMode`<>'QUICK' OR `counterpartCode`='FATHER'),
	CONSTRAINT `ckCallPageKey` CHECK (octet_length(`pageKeyHash`)=32),
	CONSTRAINT `ckCallResumePolicy` CHECK (`maxResumeAttempts`>=0 AND `resumeDelayMs`>=0),
	CONSTRAINT `ckCallLease` CHECK (`leaseExpiresAt`>`lastHeartbeatAt` AND `lastHeartbeatAt`>=`createdAt`),
	CONSTRAINT `ckCallRingingTime` CHECK (`ringingAt` IS NULL OR `ringingAt`>=`createdAt`),
	CONSTRAINT `ckCallEndTime` CHECK (`endedAt` IS NULL OR ((`ringingAt` IS NULL OR `endedAt`>=`ringingAt`) AND (`answeredAt` IS NULL OR `endedAt`>=`answeredAt`))),
	INDEX `ixCallLease` (`state`,`leaseExpiresAt`)
) ENGINE=InnoDB COMMENT='통화 세션: 세션별 미종료 통화 최대1. 종료 상태와 종료 시각·사유는 함께 존재. 게스트는 isDemographicApplied/isGenderAddressApplied=false 재개 성공 시 기존 ACTIVE 유지; 종료 사유는 웹 생명주기 기준';

CREATE TABLE `callEvent` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`callId` BINARY(16) NOT NULL COMMENT '연결된 AI 통화 ID',
	`sequence` BIGINT NOT NULL COMMENT '서버가 정한 통화별 사건 순번',
	`eventKey` BINARY(16) NOT NULL COMMENT '중복 사건 처리를 막는 클라이언트/서버 사건 UUID',
	`requestHash` VARBINARY(32) NOT NULL COMMENT '동일 eventKey의 다른 요청 본문 재사용 탐지 HMAC',
	`eventType` VARCHAR(32) NOT NULL COMMENT '통화 사건 유형',
	`stateAfter` VARCHAR(20) NOT NULL COMMENT '사건 처리 직후 통화 상태',
	`occurredAt` DATETIME(6) NOT NULL COMMENT '브라우저/서버에서 사건이 발생한 시각; 상태 순서는 서버가 검증',
	`recordedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버가 사건을 기록한 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkCallEvent1` FOREIGN KEY(`callId`) REFERENCES `callSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckCallEvent1` CHECK (`sequence`>0),
	CONSTRAINT `ckCallEvent2` CHECK (`eventType` IN ('CREATED','PREPARING','CONNECTED','RINGING_SHOWN', 'ANSWERED','CONNECTION_INTERRUPTED','GO_AWAY','RESUME_REQUESTED','RESUMED','ENDED','FAILED')),
	CONSTRAINT `ckCallEvent3` CHECK (`stateAfter` IN ('CREATED','PREPARING','RINGING','ACTIVE','ENDED','FAILED')),
	CONSTRAINT `uqCallEvent1` UNIQUE(`callId`,`sequence`),
	CONSTRAINT `uqCallEvent2` UNIQUE(`callId`,`eventKey`),
	CONSTRAINT `ckCallEventHash` CHECK (octet_length(`requestHash`)=32)
) ENGINE=InnoDB COMMENT='통화 사건: call+sequence 유일, call+eventKey 유일. 원음·텍스트 대화 없음 연결 교체 사건은 통화 상태와 별도 기록';

CREATE TABLE `connectionGrant` (
	`id` BINARY(16) NOT NULL COMMENT '발급 세대별 UUID',
	`callId` BINARY(16) NOT NULL COMMENT '연결된 AI 통화 ID',
	`generation` INT NOT NULL COMMENT '통화 내 1부터 증가하는 발급 세대',
	`purpose` VARCHAR(7) NOT NULL COMMENT 'INITIAL 또는 RESUME; 재개 핸들은 저장하지 않음',
	`keyRef` TEXT COMMENT 'READY 토큰 암호화의 외부 임시 키 참조',
	`status` VARCHAR(12) NOT NULL COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발급 작업 생성 시각; 중단된 ISSUING 정리 기준',
	`tokenCipher` BLOB  COMMENT '단기 재조회용 암호문; 연결 성공·종료·발급 기한 경과 시 즉시 제거',
	`newSessionExpiresAt` DATETIME(6)  COMMENT '이 단기 토큰으로 새 Gemini 연결을 시작할 수 있는 기한',
	`expiresAt` DATETIME(6)  COMMENT '단기 토큰/연결의 공급자 유효 기한',
	`issuedAt` DATETIME(6)  COMMENT '단기 토큰 발급 시각',
	`usedAt` DATETIME(6)  COMMENT '브라우저의 해당 세대 연결 성공 관측 시각',
	CONSTRAINT `fkConnectionGrant1` FOREIGN KEY(`callId`) REFERENCES `callSession`(`id`) ON DELETE CASCADE,
	PRIMARY KEY(`id`),
	CONSTRAINT `ckConnectionGrant1` CHECK (`status` IN ('PENDING','ISSUING','READY','USED','INVALIDATED','UNKNOWN')),
	CONSTRAINT `ckConnectionGrant2` CHECK (`status`<>'READY' OR (`tokenCipher` IS NOT NULL AND `newSessionExpiresAt` IS NOT NULL AND `expiresAt` IS NOT NULL)),
	CONSTRAINT `ckConnectionGrant3` CHECK (`status` NOT IN ('USED','INVALIDATED','UNKNOWN') OR `tokenCipher` IS NULL),
	CONSTRAINT `ckConnectionGrant4` CHECK (`expiresAt` IS NULL OR `newSessionExpiresAt`<=`expiresAt`),
	CONSTRAINT `ckGrantReadyTime` CHECK (`status`<>'READY' OR (`issuedAt` IS NOT NULL AND `newSessionExpiresAt`>`issuedAt` AND `newSessionExpiresAt`<=DATE_ADD(`issuedAt`, INTERVAL 60 SECOND))),
	CONSTRAINT `ckGrantUsedTime` CHECK (`status`<>'USED' OR `usedAt` IS NOT NULL),
	`openMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('PENDING','ISSUING','READY') THEN 1 ELSE NULL END) STORED COMMENT '진행 중인 발급 세대 최대 하나',
	`initialMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `purpose`='INITIAL' THEN 1 ELSE NULL END) STORED COMMENT '최초 발급 세대 최대 하나',
	CONSTRAINT `uqGrantGeneration` UNIQUE (`callId`,`generation`),
	CONSTRAINT `uqGrantOpen` UNIQUE (`callId`,`openMarker`),
	CONSTRAINT `uqGrantInitial` UNIQUE (`callId`,`initialMarker`),
	CONSTRAINT `ckGrantGeneration` CHECK (`generation`>0 AND ((`purpose`='INITIAL' AND `generation`=1) OR (`purpose`='RESUME' AND `generation`>1))),
	CONSTRAINT `ckGrantCipher` CHECK ((`status`='READY')=(`tokenCipher` IS NOT NULL)),
	CONSTRAINT `ckGrantKey` CHECK ((`tokenCipher` IS NULL)=(`keyRef` IS NULL)),
	INDEX `ixGrantExpiry` (`status`,`newSessionExpiresAt`)
) ENGINE=InnoDB COMMENT='통화 1:N 발급 세대. INITIAL 한 개, RESUME 여러 개; 진행 발급 한 개. 핸들은 브라우저 메모리 전용';

CREATE TABLE `apiIdempotency` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`ownerUserId` BINARY(16)  COMMENT '계정 삭제 시 함께 제거할 멱등 기록의 소유 회원',
	`ownerSessionId` BINARY(16)  COMMENT '세션 삭제 시 함께 제거할 멱등 기록의 소유 세션',
	`scopeHash` VARBINARY(32) NOT NULL COMMENT '원문 식별값 대신 사용하는 용도 분리 HMAC 범위 키',
	`operation` VARCHAR(64) NOT NULL COMMENT '고정 멱등 작업 코드; 실제 URL/UUID는 넣지 않으며 API 0.2.1 참조',
	`requestKey` BINARY(16) NOT NULL COMMENT 'Idempotency-Key UUID',
	`requestHash` VARBINARY(32) NOT NULL COMMENT '정규화 요청의 HMAC; 동일 키에 다른 본문 제출 탐지',
	`status` VARCHAR(11) NOT NULL COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`resourceId` BINARY(16)  COMMENT 'operation으로 종류를 구분하는 결과 리소스 UUID; 범용 FK가 아님',
	`responseCipher` BLOB  COMMENT '동일 응답 재생을 위한 단기 암호화 응답; 영구 응답 캐시 아님',
	`responseExpiresAt` DATETIME(6)  COMMENT '민감한 재생 응답 암호문 삭제 기한',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`expiresAt` DATETIME(6) NOT NULL COMMENT '사용/보관 만료 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkApiIdempotency1` FOREIGN KEY(`ownerUserId`) REFERENCES `appUser`(`id`) ON DELETE CASCADE,
	CONSTRAINT `fkApiIdempotency2` FOREIGN KEY(`ownerSessionId`) REFERENCES `webSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckApiIdempotency1` CHECK (octet_length(`scopeHash`)=32),
	CONSTRAINT `ckApiIdempotency2` CHECK (octet_length(`requestHash`)=32),
	CONSTRAINT `ckApiIdempotency3` CHECK (`status` IN ('IN_PROGRESS','DONE','UNKNOWN')),
	CONSTRAINT `uqApiIdempotency1` UNIQUE(`scopeHash`,`operation`,`requestKey`),
	CONSTRAINT `ckApiIdempotency4` CHECK (`expiresAt`>`createdAt`),
	INDEX `ixIdempotencyExpiry` (`expiresAt`),
	INDEX `ixIdempotencyUser` (`ownerUserId`),
	INDEX `ixIdempotencySession` (`ownerSessionId`)
) ENGINE=InnoDB COMMENT='멱등 처리: 범위+operation+key 유일. 같은 키의 다른 내용 거절. resourceId는 operation별 해석하는 보조 참조이며 FK 아님';

CREATE TABLE `rateBucket` (
	`scopeKind` VARCHAR(12) NOT NULL COMMENT 'USER/SESSION/IP 요청 제한 범위',
	`scopeHash` VARBINARY(32) NOT NULL COMMENT '원문 식별값 대신 사용하는 용도 분리 HMAC 범위 키',
	`operation` VARCHAR(24) NOT NULL COMMENT 'API operation 또는 제한 작업 코드',
	`windowStart` DATETIME(6) NOT NULL COMMENT '요청 제한 창 시작 시각',
	`windowSeconds` INT NOT NULL COMMENT '제한 창 길이(초)',
	`usedCount` INT NOT NULL DEFAULT 0 COMMENT '해당 창에서 차감된 요청 명령 수',
	`expiresAt` DATETIME(6) NOT NULL COMMENT '사용/보관 만료 시각',
	CONSTRAINT `ckRateBucket1` CHECK (`scopeKind` IN ('USER','SESSION','IP')),
	CONSTRAINT `ckRateBucket2` CHECK (octet_length(`scopeHash`)=32),
	CONSTRAINT `ckRateBucket3` CHECK (`windowSeconds`>0),
	CONSTRAINT `ckRateBucket4` CHECK (`usedCount`>=0),
	PRIMARY KEY(`scopeKind`,`scopeHash`,`operation`,`windowStart`,`windowSeconds`),
	INDEX `ixRateExpiry` (`expiresAt`)
) ENGINE=InnoDB COMMENT='요청 횟수 창: 사용자/웹 세션/시간대별 원자적 제한. IP는 원문 대신 짧은 보관의 HMAC';

CREATE TABLE `operationEvent` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`sessionId` BINARY(16) NOT NULL COMMENT '소유 웹 세션 식별자',
	`callId` BINARY(16)  COMMENT '연결된 AI 통화 ID',
	`eventKey` BINARY(16) NOT NULL COMMENT '중복 사건 처리를 막는 클라이언트/서버 사건 UUID',
	`category` VARCHAR(16) NOT NULL COMMENT '인증/권한/통화/오디오/제스처/위치 품질/웹 작성 화면/SOS 안내/대체 통화 구분',
	`code` VARCHAR(48) NOT NULL COMMENT '문서/상황/상대 또는 허용된 운영 이벤트 코드; 해당 CHECK 및 API 허용목록 참조',
	`isSuccess` TINYINT(1)  COMMENT '관측 동작의 성공 여부; 미확인 시 NULL',
	`latencyMs` INT  COMMENT '개인정보 없이 측정한 처리 지연 밀리초',
	`networkType` VARCHAR(8)  COMMENT 'WIFI/CELLULAR/OFFLINE/UNKNOWN 관측 네트워크',
	`webVersion` VARCHAR(40)  COMMENT '공개 웹 배포 버전; 브라우저 지문 아님',
	`occurredAt` DATETIME(6) NOT NULL COMMENT '브라우저/서버에서 사건이 발생한 시각; 상태 순서는 서버가 검증',
	`recordedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버가 사건을 기록한 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkOperationEvent2` FOREIGN KEY(`sessionId`) REFERENCES `webSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `fkOperationEvent3` FOREIGN KEY(`callId`) REFERENCES `callSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckOperationEvent1` CHECK (`category` IN ('AUTH','PERMISSION','CALL','AUDIO','GESTURE','LOCATION','MESSAGE_COMPOSER','SOS','FALLBACK')),
	CONSTRAINT `ckOperationEvent2` CHECK (`isSuccess` IN (0,1)),
	CONSTRAINT `ckOperationEvent3` CHECK (`latencyMs`>=0),
	CONSTRAINT `ckOperationEvent4` CHECK (`networkType` IN ('WIFI','CELLULAR','OFFLINE','UNKNOWN')),
	CONSTRAINT `uqOperationEvent1` UNIQUE(`sessionId`,`eventKey`),
	INDEX `ixOperationTime` (`recordedAt`),
	INDEX `ixOperationCall` (`callId`),
	CONSTRAINT `fkOperationCallOwner` FOREIGN KEY (`callId`,`sessionId`) REFERENCES `callSession` (`id`,`sessionId`) ON DELETE CASCADE,
	CONSTRAINT `ckOperationCode` CHECK (`code` IN ('AUTH_SUCCEEDED','MICROPHONE_PERMISSION_REVIEWED','LOCATION_PERMISSION_REVIEWED','LIVE_CONNECT_STARTED','LIVE_CONNECT_SUCCEEDED','LIVE_CONNECT_FAILED','RINGING_SHOWN','RINGING_FAILED','FIRST_AUDIO_PLAYED','AUDIO_INTERRUPTED','PAGE_EXITED','QUICK_START_SELECTED','QUICK_START_CANCELLED','LOCATION_AVAILABLE','LOCATION_UNAVAILABLE','COMPOSER_OPENED','COMPOSER_OPEN_FAILED','SOS_GUIDE_VIEWED','SOS_GUIDE_FAILED','FALLBACK_STARTED','FALLBACK_ENDED','LIVE_RESUME_STARTED','LIVE_RESUME_SUCCEEDED','LIVE_RESUME_FAILED','PERMISSION_QUERY_UNAVAILABLE','PAGE_RELOADED'))
) ENGINE=InnoDB COMMENT='운영 사건: 임의 JSON·음성·대화·번호·위치·토큰 필드 없음. 클라이언트 report를 인증 사실로 사용하지 않음 userId 중복 저장 없이 webSession에서 소유자 도출';

CREATE TABLE `deletionJob` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`userId` BINARY(16)  COMMENT '소유 회원 식별자',
	`scope` VARCHAR(16) NOT NULL COMMENT '삭제 범위 ACCOUNT/USAGE_HISTORY 또는 동의 철회용 AI_DATA/LOCATION_DATA',
	`accountSubjectHash` VARBINARY(32)  COMMENT 'ACCOUNT 외부 정리 중 같은 Kakao 계정 재가입 차단용 HMAC; 완료 즉시 제거',
	`status` VARCHAR(13) NOT NULL DEFAULT 'PENDING' COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`receiptHash` VARBINARY(32) NOT NULL COMMENT '삭제 이후 상태 조회용 난수 접수증 토큰 해시',
	`cleanupCipher` BLOB  COMMENT '외부 키 폐기/카카오 연결 해제 등에 필요한 최소 단기 작업 암호문',
	`cleanupKeyRef` TEXT COMMENT '회원 삭제 후에도 작업 수행 가능한 별도 임시 암호화 키 참조',
	`requestedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '삭제/정리 요청을 접수한 시각',
	`cutoffAt` DATETIME(6) NOT NULL COMMENT '해당 시각까지 생성된 데이터의 삭제 기준',
	`dueAt` DATETIME(6) NOT NULL COMMENT '작업 완료 목표 기한',
	`completedAt` DATETIME(6)  COMMENT '작업/시도 최종 완료 시각',
	`errorCode` VARCHAR(40)  COMMENT '개인정보 없는 내부 표준 오류 코드',
	`receiptExpiresAt` DATETIME(6) NOT NULL COMMENT '접수증 조회 권한 만료 시각',
	`pendingMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('PENDING','PROCESSING','LOCAL_DELETED') THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkDeletionJob1` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`) ON DELETE SET NULL,
	CONSTRAINT `ckDeletionJob1` CHECK (`scope` IN ('ACCOUNT','USAGE_HISTORY','AI_DATA','LOCATION_DATA')),
	CONSTRAINT `ckDeletionJob2` CHECK (`status` IN ('PENDING','PROCESSING','LOCAL_DELETED','COMPLETED','FAILED')),
	CONSTRAINT `ckDeletionJob3` CHECK (octet_length(`receiptHash`)=32),
	CONSTRAINT `uqDeletionJob1` UNIQUE(`receiptHash`),
	CONSTRAINT `ckDeletionJob4` CHECK ((`status`='COMPLETED') = (`completedAt` IS NOT NULL)),
	CONSTRAINT `uqDeletionJob2` UNIQUE(`userId`,`scope`,`pendingMarker`),
	CONSTRAINT `ckDeletionSubjectLength` CHECK (`accountSubjectHash` IS NULL OR octet_length(`accountSubjectHash`)=32),
	CONSTRAINT `ckDeletionSubjectScope` CHECK (`accountSubjectHash` IS NULL OR `scope`='ACCOUNT'),
	CONSTRAINT `ckDeletionSubjectPending` CHECK (`scope`<>'ACCOUNT' OR `status` NOT IN ('PENDING','PROCESSING','LOCAL_DELETED') OR `accountSubjectHash` IS NOT NULL),
	CONSTRAINT `ckDeletionSubjectCompleted` CHECK (`status`<>'COMPLETED' OR `accountSubjectHash` IS NULL),
	CONSTRAINT `uqDeletionPendingSubject` UNIQUE (`accountSubjectHash`,`pendingMarker`),
	INDEX `ixDeletionDue` (`status`,`dueAt`),
	CONSTRAINT `ckDeletionCleanupKey` CHECK ((`cleanupCipher` IS NULL)=(`cleanupKeyRef` IS NULL)),
	CONSTRAINT `ckDeletionCleanupDone` CHECK (`status`<>'COMPLETED' OR `cleanupCipher` IS NULL),
	CONSTRAINT `ckDeletionTimes` CHECK (`dueAt`>=`requestedAt` AND `receiptExpiresAt`>`requestedAt` AND (`completedAt` IS NULL OR `completedAt`>=`requestedAt`))
) ENGINE=InnoDB COMMENT='삭제 작업: 범위별 미완료 작업 최대1. 계정 삭제 후 userId는 null, 별도 HttpOnly 접수증 쿠키으로 작업 상태만 조회';

START TRANSACTION;
INSERT INTO `scenario` (`code`,`label`,`sortOrder`) VALUES
 ('FOLLOWED','누군가 따라오는 것 같아요',1),
 ('UNSAFE_TAXI','택시 안이 불안해요',2),
 ('STRANGER_NEARBY','낯선 사람이 근처에 있어요',3),
 ('WALKING_ALONE','혼자 귀가하기 무서워요',4);
INSERT INTO `counterpart` (`code`,`label`,`displayName`,`sortOrder`) VALUES
 ('FATHER','아빠','아빠',1),('MOTHER','엄마','엄마',2),('FRIEND','친구','친구',3);
COMMIT;

-- serviceDocument와 promptRelease/personaPrompt는 검수된 본문·모델·음성으로 별도 발행.
-- 발행 서비스가 정확히 12개 페르소나와 안전성 검증을 확인한다.
-- 잠금 순서: appUser → webSession → callSession → connectionGrant. 외부 호출 중 DB 잠금 금지.
-- 종료 상태 되돌리기 금지, 재개 세대/만료/권한/동의는 API 및 서비스 트랜잭션에서 검증.
-- 직접 Gemini WSS 해제를 Spring 서버가 관측한다고 가정하지 않는다. heartbeat lease로 정리.
-- 삭제 순서: webSession(CASCADE 통화/발급/사건/멱등) → appUser(CASCADE 연락망/동의/설정).
-- operationEvent는 webSession에서 회원을 도출한다. deletionJob.userId는 SET NULL.
-- 보관 기간·운영 정책·데이터 이관은 논리 설계 T01~T06 및 최종 운영 정책/변경 대응표 참조.

-- 동의 문서는 최초 발행 버전 고정. CONSENTS는 호환성 예약값으로 서비스 전이 금지.
-- 삭제 FAILED는 대상 데이터/키 유지 및 전체 롤백인 경우만. LOCAL_DELETED는 외부 정리 진행.
-- 멱등 operation은 고정 코드, 소유자와 대상은 scopeHash에 포함(API 0.2.1).

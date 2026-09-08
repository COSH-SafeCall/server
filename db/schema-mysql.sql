-- SafeCall 물리 DB 스키마 v2.1 / 2026-09-08
-- 기준: 수정된 기능 요구사항, 비기능 요구사항, 프로젝트 개요.
-- DBMS: 사용자 확정 지시로 MySQL 유지. 개요의 PostgreSQL 표기보다 우선한다.
-- MySQL 8.0.41 이상 8.0/8.4 계열, InnoDB, utf8mb4_0900_as_cs.
-- 빈 safecall DB에 1회 적용하는 신규 설치 DDL이다. 기존 DB 변경/데이터 이관용이 아니다.
-- CREATE DATABASE가 실패하면 즉시 중단한다. mysql --force 사용 금지.
-- DDL 암묵적 커밋에 유의. 운영 DB 적용은 수행하지 않았다.
-- 연계: API_명세.md, DB_논리_설계.md, 변경_대응표.md, verification/README.md
-- UUID BINARY(16), UUID_TO_BIN(value,0)/BIN_TO_UUID(value,0), 시간 UTC DATETIME(6).
-- 식별자는 camelCase와 백틱 인용. Boolean은 is 접두사와 0/1 CHECK.
-- Cipher: 앱 계층 인증 암호화 봉투. keyRef: 외부 키 참조이며 키 자체 아님.
-- Hash: 용도 분리 HMAC-SHA-256. 본인/보호자 번호 비교 도메인은 PHONE_MATCH:userId로 동일.
-- 통화 음성/대화/개인별 완성 프롬프트/현재 좌표/지도 URL/문자 본문은 영속 저장하지 않는다.
-- 안심 메시지는 Android 문자앱 Intent로 작성한다. 발송 큐/전달 결과 테이블은 없다.

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

CREATE TABLE `deviceInstallation` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`installationHash` VARBINARY(32) NOT NULL COMMENT '앱 설치 단위 난수의 HMAC; 인증 신원 증거로 사용하지 않음',
	`keyRef` TEXT NOT NULL COMMENT 'DB 외부 암호화 키의 참조값(키 원문 아님)',
	`platform` VARCHAR(7) NOT NULL DEFAULT 'ANDROID' COMMENT '대상 플랫폼 ANDROID 고정',
	`appVersion` VARCHAR(40) NOT NULL COMMENT '앱 배포 버전',
	`osVersion` VARCHAR(40) NOT NULL COMMENT 'Android OS 버전',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`lastSeenAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '설치의 마지막 서버 관측 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `ckDeviceInstallation1` CHECK (octet_length(`installationHash`)=32),
	CONSTRAINT `uqDeviceInstallation1` UNIQUE(`installationHash`),
	CONSTRAINT `ckDeviceInstallation2` CHECK (`platform`='ANDROID')
) ENGINE=InnoDB COMMENT='앱 설치: 설치 ID 자체는 인증 수단 아님. IMEI/시리얼/주소록 없음';

CREATE TABLE `deviceSession` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`installationId` BINARY(16) NOT NULL COMMENT '요청이 발생한 앱 설치 식별자',
	`userId` BINARY(16)  COMMENT '소유 회원 식별자',
	`kind` VARCHAR(6) NOT NULL COMMENT '회원/게스트 구분 GUEST/KAKAO',
	`onboardingStep` VARCHAR(24) NOT NULL COMMENT '현재 온보딩 단계; 게스트는 권한/SOS/완료 단계만 허용',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '온보딩 단계 변경 버전',
	`status` VARCHAR(7) NOT NULL DEFAULT 'ACTIVE' COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`expiresAt` DATETIME(6) NOT NULL COMMENT '사용/보관 만료 시각',
	`revokedAt` DATETIME(6)  COMMENT '세션 명시적 폐기 시각; REVOKED와 함께 설정',
	`activeMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `status`='ACTIVE' THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkDeviceSession1` FOREIGN KEY(`installationId`) REFERENCES `deviceInstallation`(`id`),
	CONSTRAINT `fkDeviceSession2` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`),
	CONSTRAINT `ckDeviceSession1` CHECK (`kind` IN ('GUEST','KAKAO')),
	CONSTRAINT `ckDeviceSession2` CHECK (`onboardingStep` IN ('PROFILE','CONTACTS','CONSENTS','PERMISSIONS','SOS_GUIDE','MESSAGE_TEST','COMPLETE')),
	CONSTRAINT `ckDeviceSession3` CHECK (`status` IN ('ACTIVE','REVOKED','EXPIRED')),
	CONSTRAINT `ckDeviceSession4` CHECK ((`kind`='GUEST' AND `userId` IS NULL) OR (`kind`='KAKAO' AND `userId` IS NOT NULL)),
	CONSTRAINT `ckDeviceSession5` CHECK (`expiresAt`>`createdAt`),
	CONSTRAINT `ckDeviceSession6` CHECK (`kind`<>'GUEST' OR `onboardingStep` IN ('PERMISSIONS','SOS_GUIDE','COMPLETE')),
	CONSTRAINT `ckDeviceSession7` CHECK ((`status`='REVOKED') = (`revokedAt` IS NOT NULL)),
	CONSTRAINT `uqDeviceSession1` UNIQUE(`installationId`,`activeMarker`),
	INDEX `ixSessionUser` (`userId`),
	INDEX `ixSessionExpiry` (`status`,`expiresAt`),
	CONSTRAINT `ckDeviceSessionVersion` CHECK (`version`>0),
	CONSTRAINT `ckGuestLifetime` CHECK (`kind`<>'GUEST' OR `expiresAt`<=DATE_ADD(`createdAt`, INTERVAL 24 HOUR))
) ENGINE=InnoDB COMMENT='기기 세션: GUEST이면 userId 없음, KAKAO이면 필수. 설치별 ACTIVE 세션 최대1, 게스트 최대24시간';

CREATE TABLE `sessionCredential` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`sessionId` BINARY(16) NOT NULL COMMENT '소유 기기 세션 식별자',
	`generation` INT NOT NULL COMMENT '토큰 회전 세대 번호; 세션 내 유일',
	`accessHash` VARBINARY(32) NOT NULL COMMENT 'access token 검증용 해시(원문 미저장)',
	`refreshHash` VARBINARY(32) NOT NULL COMMENT 'refresh token 검증/재사용 탐지용 해시(원문 미저장)',
	`accessExpiresAt` DATETIME(6) NOT NULL COMMENT 'access token 만료 시각',
	`refreshExpiresAt` DATETIME(6) NOT NULL COMMENT 'refresh token 만료 시각',
	`consumedAt` DATETIME(6)  COMMENT 'refresh token을 사용하여 회전한 시각; NULL이면 현재 세대',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '행 생성 시각(UTC)',
	`currentMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `consumedAt` IS NULL THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkSessionCredential1` FOREIGN KEY(`sessionId`) REFERENCES `deviceSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckSessionCredential1` CHECK (`generation`>0),
	CONSTRAINT `ckSessionCredential2` CHECK (octet_length(`accessHash`)=32),
	CONSTRAINT `uqSessionCredential1` UNIQUE(`accessHash`),
	CONSTRAINT `ckSessionCredential3` CHECK (octet_length(`refreshHash`)=32),
	CONSTRAINT `uqSessionCredential2` UNIQUE(`refreshHash`),
	CONSTRAINT `uqSessionCredential3` UNIQUE(`sessionId`,`generation`),
	CONSTRAINT `ckSessionCredential4` CHECK (`accessExpiresAt`>`createdAt` AND `refreshExpiresAt`>=`accessExpiresAt`),
	CONSTRAINT `uqSessionCredential4` UNIQUE(`sessionId`,`currentMarker`)
) ENGINE=InnoDB COMMENT='세션 자격 증명: 세션별 미소비 generation 최대1. 이전 해시는 refresh 재사용 탐지용으로 만료까지 유지';

CREATE TABLE `userSetting` (
	`userId` BINARY(16) NOT NULL COMMENT '소유 회원 식별자',
	`incomingAlertMode` VARCHAR(8) NOT NULL DEFAULT 'RINGTONE' COMMENT '가상 수신 알림: RINGTONE/VIBRATE/SILENT; OS 설정과 별개',
	`updatedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '최종 수정 시각; 서비스 UPDATE에서 명시적으로 갱신',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전; 변경 성공 시 1 증가',
	CONSTRAINT `fkUserSetting1` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`) ON DELETE CASCADE,
	PRIMARY KEY(`userId`),
	CONSTRAINT `ckUserSetting1` CHECK (`incomingAlertMode` IN ('RINGTONE','VIBRATE','SILENT')),
	CONSTRAINT `ckUserSetting2` CHECK (`version`>0)
) ENGINE=InnoDB COMMENT='사용자 설정: 회원별 1개; RINGTONE 기본, VIBRATE/SILENT 허용. 기기 권한을 설정 칼럼으로 저장하지 않음';

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
	`sessionId` BINARY(16) NOT NULL COMMENT '소유 기기 세션 식별자',
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
	`expiresAt` DATETIME(6) NOT NULL COMMENT '이 통화의 최대 수명 만료; 앱에 사전 종료 안내',
	`version` BIGINT NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전; 변경 성공 시 1 증가',
	`activeMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `state` IN ('CREATED','PREPARING','RINGING','ACTIVE') THEN 1 ELSE NULL END) STORED COMMENT '조건을 만족하는 행은 1, 그 외 NULL; 조건부 유일성을 강제하는 DB 생성 컬럼. API 입력/수정 금지',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkCallSession1` FOREIGN KEY(`sessionId`) REFERENCES `deviceSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckCallSession1` CHECK (`state` IN ('CREATED','PREPARING','RINGING','ACTIVE','ENDED','FAILED')),
	CONSTRAINT `ckCallSession2` CHECK (`endReason` IN ('USER_ENDED','DECLINED','BACK_NAVIGATION','APP_BACKGROUND', 'HOME_BUTTON','EMERGENCY_SCREEN','SWITCH_TO_FALLBACK','CONNECTION_FAILED','RINGING_FAILED', 'MICROPHONE_FAILED','AUDIO_FAILED','CONNECTION_LOST','SESSION_EXPIRED','DURATION_LIMIT', 'LOGOUT','CONSENT_WITHDRAWN','DATA_DELETION')),
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
	CONSTRAINT `ckQuickFather` CHECK (`startMode`<>'QUICK' OR `counterpartCode`='FATHER')
) ENGINE=InnoDB COMMENT='통화 세션: 세션별 미종료 통화 최대1. 종료 상태와 종료 시각·사유는 함께 존재. 게스트는 isDemographicApplied/isGenderAddressApplied=false';

CREATE TABLE `callEvent` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`callId` BINARY(16) NOT NULL COMMENT '연결된 AI 통화 ID',
	`sequence` BIGINT NOT NULL COMMENT '서버가 정한 통화별 사건 순번',
	`eventKey` BINARY(16) NOT NULL COMMENT '중복 사건 처리를 막는 클라이언트/서버 사건 UUID',
	`requestHash` VARBINARY(32) NOT NULL COMMENT '동일 eventKey의 다른 요청 본문 재사용 탐지 HMAC',
	`eventType` VARCHAR(32) NOT NULL COMMENT '통화 사건 유형',
	`stateAfter` VARCHAR(20) NOT NULL COMMENT '사건 처리 직후 통화 상태',
	`occurredAt` DATETIME(6) NOT NULL COMMENT '앱/서버에서 사건이 발생한 시각; 상태 순서는 서버가 검증',
	`recordedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버가 사건을 기록한 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkCallEvent1` FOREIGN KEY(`callId`) REFERENCES `callSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckCallEvent1` CHECK (`sequence`>0),
	CONSTRAINT `ckCallEvent2` CHECK (`eventType` IN ('CREATED','PREPARING','CONNECTED','RINGING_SHOWN', 'ANSWERED','ENDED','FAILED')),
	CONSTRAINT `ckCallEvent3` CHECK (`stateAfter` IN ('CREATED','PREPARING','RINGING','ACTIVE','ENDED','FAILED')),
	CONSTRAINT `uqCallEvent1` UNIQUE(`callId`,`sequence`),
	CONSTRAINT `uqCallEvent2` UNIQUE(`callId`,`eventKey`),
	CONSTRAINT `ckCallEventHash` CHECK (octet_length(`requestHash`)=32)
) ENGINE=InnoDB COMMENT='통화 사건: call+sequence 유일, call+eventKey 유일. 원음·텍스트 대화 없음';

CREATE TABLE `connectionGrant` (
	`callId` BINARY(16) NOT NULL COMMENT '연결된 AI 통화 ID',
	`status` VARCHAR(12) NOT NULL COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발급 작업 생성 시각; 중단된 ISSUING 정리 기준',
	`tokenCipher` BLOB  COMMENT 'Gemini 단기 토큰 암호문; 사용/종료/만료 시 즉시 삭제',
	`newSessionExpiresAt` DATETIME(6)  COMMENT '이 단기 토큰으로 새 Gemini 연결을 시작할 수 있는 기한',
	`expiresAt` DATETIME(6)  COMMENT '단기 토큰/연결의 공급자 유효 기한',
	`issuedAt` DATETIME(6)  COMMENT '단기 토큰 발급 시각',
	`usedAt` DATETIME(6)  COMMENT '앱의 Gemini 연결 성공 관측 시각',
	CONSTRAINT `fkConnectionGrant1` FOREIGN KEY(`callId`) REFERENCES `callSession`(`id`) ON DELETE CASCADE,
	PRIMARY KEY(`callId`),
	CONSTRAINT `ckConnectionGrant1` CHECK (`status` IN ('PENDING','ISSUING','READY','USED','INVALIDATED','UNKNOWN')),
	CONSTRAINT `ckConnectionGrant2` CHECK (`status`<>'READY' OR (`tokenCipher` IS NOT NULL AND `newSessionExpiresAt` IS NOT NULL AND `expiresAt` IS NOT NULL)),
	CONSTRAINT `ckConnectionGrant3` CHECK (`status` NOT IN ('USED','INVALIDATED','UNKNOWN') OR `tokenCipher` IS NULL),
	CONSTRAINT `ckConnectionGrant4` CHECK (`expiresAt` IS NULL OR `newSessionExpiresAt`<=`expiresAt`),
	CONSTRAINT `ckGrantReadyTime` CHECK (`status`<>'READY' OR (`issuedAt` IS NOT NULL AND `newSessionExpiresAt`>`issuedAt` AND `newSessionExpiresAt`<=DATE_ADD(`issuedAt`, INTERVAL 60 SECOND))),
	CONSTRAINT `ckGrantUsedTime` CHECK (`status`<>'USED' OR `usedAt` IS NOT NULL)
) ENGINE=InnoDB COMMENT='단기 연결 grant: call당 최대1. USED/INVALIDATED/UNKNOWN에서는 암호문 없음. 정상 연결 또는 종료 즉시 암호문 폐기';

CREATE TABLE `apiIdempotency` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`ownerUserId` BINARY(16)  COMMENT '계정 삭제 시 함께 제거할 멱등 기록의 소유 회원',
	`ownerSessionId` BINARY(16)  COMMENT '세션 삭제 시 함께 제거할 멱등 기록의 소유 세션',
	`scopeHash` VARBINARY(32) NOT NULL COMMENT '원문 식별값 대신 사용하는 용도 분리 HMAC 범위 키',
	`operation` VARCHAR(64) NOT NULL COMMENT 'API operation 또는 제한 작업 코드',
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
	CONSTRAINT `fkApiIdempotency2` FOREIGN KEY(`ownerSessionId`) REFERENCES `deviceSession`(`id`) ON DELETE CASCADE,
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
	`scopeKind` VARCHAR(12) NOT NULL COMMENT 'USER/INSTALLATION/IP 요청 제한 범위',
	`scopeHash` VARBINARY(32) NOT NULL COMMENT '원문 식별값 대신 사용하는 용도 분리 HMAC 범위 키',
	`operation` VARCHAR(24) NOT NULL COMMENT 'API operation 또는 제한 작업 코드',
	`windowStart` DATETIME(6) NOT NULL COMMENT '요청 제한 창 시작 시각',
	`windowSeconds` INT NOT NULL COMMENT '제한 창 길이(초)',
	`usedCount` INT NOT NULL DEFAULT 0 COMMENT '해당 창에서 차감된 요청 명령 수',
	`expiresAt` DATETIME(6) NOT NULL COMMENT '사용/보관 만료 시각',
	CONSTRAINT `ckRateBucket1` CHECK (`scopeKind` IN ('USER','INSTALLATION','IP')),
	CONSTRAINT `ckRateBucket2` CHECK (octet_length(`scopeHash`)=32),
	CONSTRAINT `ckRateBucket3` CHECK (`windowSeconds`>0),
	CONSTRAINT `ckRateBucket4` CHECK (`usedCount`>=0),
	PRIMARY KEY(`scopeKind`,`scopeHash`,`operation`,`windowStart`,`windowSeconds`),
	INDEX `ixRateExpiry` (`expiresAt`)
) ENGINE=InnoDB COMMENT='요청 횟수 창: 사용자/설치/시간대별 원자적 제한. IP는 원문 대신 짧은 보관의 HMAC';

CREATE TABLE `operationEvent` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`userId` BINARY(16)  COMMENT '소유 회원 식별자',
	`sessionId` BINARY(16) NOT NULL COMMENT '소유 기기 세션 식별자',
	`callId` BINARY(16)  COMMENT '연결된 AI 통화 ID',
	`eventKey` BINARY(16) NOT NULL COMMENT '중복 사건 처리를 막는 클라이언트/서버 사건 UUID',
	`category` VARCHAR(16) NOT NULL COMMENT '인증/권한/통화/오디오/제스처/위치 품질/문자앱 호출/SOS 안내/대체 통화 구분',
	`code` VARCHAR(48) NOT NULL COMMENT '문서/상황/상대 또는 허용된 운영 이벤트 코드; 해당 CHECK 및 API 허용목록 참조',
	`isSuccess` TINYINT(1)  COMMENT '관측 동작의 성공 여부; 미확인 시 NULL',
	`latencyMs` INT  COMMENT '개인정보 없이 측정한 처리 지연 밀리초',
	`networkType` VARCHAR(8)  COMMENT 'WIFI/CELLULAR/OFFLINE/UNKNOWN 관측 네트워크',
	`appVersion` VARCHAR(40)  COMMENT '앱 배포 버전',
	`occurredAt` DATETIME(6) NOT NULL COMMENT '앱/서버에서 사건이 발생한 시각; 상태 순서는 서버가 검증',
	`recordedAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '서버가 사건을 기록한 시각',
	PRIMARY KEY(`id`),
	CONSTRAINT `fkOperationEvent1` FOREIGN KEY(`userId`) REFERENCES `appUser`(`id`) ON DELETE CASCADE,
	CONSTRAINT `fkOperationEvent2` FOREIGN KEY(`sessionId`) REFERENCES `deviceSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `fkOperationEvent3` FOREIGN KEY(`callId`) REFERENCES `callSession`(`id`) ON DELETE CASCADE,
	CONSTRAINT `ckOperationEvent1` CHECK (`category` IN ('AUTH','PERMISSION','CALL','AUDIO','GESTURE','LOCATION','MESSAGE_COMPOSER','SOS','FALLBACK')),
	CONSTRAINT `ckOperationEvent2` CHECK (`isSuccess` IN (0,1)),
	CONSTRAINT `ckOperationEvent3` CHECK (`latencyMs`>=0),
	CONSTRAINT `ckOperationEvent4` CHECK (`networkType` IN ('WIFI','CELLULAR','OFFLINE','UNKNOWN')),
	CONSTRAINT `uqOperationEvent1` UNIQUE(`sessionId`,`eventKey`),
	INDEX `ixOperationTime` (`recordedAt`),
	INDEX `ixOperationCall` (`callId`),
	INDEX `ixOperationUser` (`userId`),
	CONSTRAINT `fkOperationCallOwner` FOREIGN KEY (`callId`,`sessionId`) REFERENCES `callSession` (`id`,`sessionId`) ON DELETE CASCADE,
	CONSTRAINT `ckOperationCode` CHECK (`code` IN ('AUTH_SUCCEEDED','MICROPHONE_PERMISSION_REVIEWED','LOCATION_PERMISSION_REVIEWED','LIVE_CONNECT_STARTED','LIVE_CONNECT_SUCCEEDED','LIVE_CONNECT_FAILED','RINGING_SHOWN','RINGING_FAILED','FIRST_AUDIO_PLAYED','AUDIO_INTERRUPTED','APP_EXITED','QUICK_START_SELECTED','QUICK_START_CANCELLED','LOCATION_AVAILABLE','LOCATION_UNAVAILABLE','COMPOSER_OPENED','COMPOSER_OPEN_FAILED','SOS_GUIDE_VIEWED','SOS_SETTINGS_OPEN_FAILED','FALLBACK_STARTED','FALLBACK_ENDED'))
) ENGINE=InnoDB COMMENT='운영 사건: 임의 JSON·음성·대화·번호·위치·토큰 필드 없음. 클라이언트 report를 인증 사실로 사용하지 않음';

CREATE TABLE `deletionJob` (
	`id` BINARY(16) NOT NULL COMMENT '서버가 생성한 UUID 식별자',
	`userId` BINARY(16)  COMMENT '소유 회원 식별자',
	`scope` VARCHAR(16) NOT NULL COMMENT '삭제 범위 ACCOUNT/USAGE_HISTORY 또는 동의 철회용 AI_DATA/LOCATION_DATA',
	`accountSubjectHash` VARBINARY(32)  COMMENT 'ACCOUNT 외부 정리 중 같은 Kakao 계정 재가입 차단용 HMAC; 완료 즉시 제거',
	`status` VARCHAR(13) NOT NULL DEFAULT 'PENDING' COMMENT '처리/활성 상태; 허용값과 연관 조건은 아래 CHECK 참조',
	`receiptHash` VARBINARY(32) NOT NULL COMMENT '삭제 이후 상태 조회용 난수 접수증 토큰 해시',
	`cleanupCipher` BLOB  COMMENT '외부 키 폐기/카카오 연결 해제 등에 필요한 최소 단기 작업 암호문',
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
	INDEX `ixDeletionDue` (`status`,`dueAt`)
) ENGINE=InnoDB COMMENT='삭제 작업: 범위별 미완료 작업 최대1. 계정 삭제 후 userId는 null, 별도 접수증으로 작업 상태만 조회';

START TRANSACTION;
INSERT INTO `scenario` (`code`,`label`,`sortOrder`) VALUES
 ('FOLLOWED','누군가 따라오는 것 같아요',1),
 ('UNSAFE_TAXI','택시 안이 불안해요',2),
 ('STRANGER_NEARBY','낯선 사람이 근처에 있어요',3),
 ('WALKING_ALONE','혼자 귀가하기 무서워요',4);
INSERT INTO `counterpart` (`code`,`label`,`displayName`,`sortOrder`) VALUES
 ('FATHER','아빠','아빠',1),('MOTHER','엄마','엄마',2),('FRIEND','친구','친구',3);
COMMIT;

-- 배포 전 검수된 serviceDocument, promptRelease, personaPrompt 12행을 별도 입력한다.
-- 미검수 프롬프트/실제 모델명/정책 본문을 seed로 발행하지 않는다.
-- 조건부 UNIQUE는 최대 개수만 보장한다. 정확히 12개와 안전성 검수는 발행 서비스에서 검사한다.
-- version/updatedAt은 서비스 UPDATE에서 갱신. 종료 명령과 중복 이벤트는 논리 DB T03을 따른다.
-- DB의 행 상태가 Gemini의 원격 연결 종료를 직접 보장하지 않는다. 앱이 로컬 소켓을 닫는다.
-- 연락망/동의/계정 변경: appUser → deviceInstallation → deviceSession → callSession 순으로 잠근다.
-- 외부 OAuth/Gemini 호출 중 DB 트랜잭션을 열어 두지 않는다.
-- 삭제 순서: deviceSession(연관 credential/call/event/grant 삭제) → appUser(연락망/설정/동의 삭제).
-- 계정 삭제 후 deletionJob은 userId=NULL, 접수증 해시로 상태만 제공한다.
-- 자세한 트랜잭션/보관/삭제/서비스 계층 검증 규칙은 DB_논리_설계.md T01~T06 참조.

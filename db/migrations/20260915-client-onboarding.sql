-- 프론트엔드 온보딩 전환. 20260915-static-service-documents.sql 이후 1회 적용.
-- 서버/worker를 중지하고 백업 후 실행한다. 구형 서버와 혼용하지 않는다.
-- 실제 프로필/동의/연락처/삭제 작업은 보존한다. DDL은 암묵적 COMMIT이다.
USE `safecall`;

-- 구형 로그인 동의 스냅샷을 사용 중인 OAuth는 새로 시작해야 한다.
UPDATE `oauthAttempt` SET `status`='EXPIRED',`completedAt`=UTC_TIMESTAMP(6)
 WHERE `status` IN ('PENDING','EXCHANGING');

DROP TABLE `oauthConsent`;

ALTER TABLE `webSession`
 DROP CHECK `ckWebSessionStep`,
 DROP CHECK `ckWebSessionGuestStep`,
 DROP CHECK `ckWebSessionAnonymousStep`,
 DROP CHECK `ckWebSessionVersion`,
 DROP COLUMN `onboardingStep`,
 DROP COLUMN `version`;

-- ACTIVE는 인증된 계정 상태다. 서비스 기능은 실제 동의/프로필로 검증한다.
UPDATE `appUser` SET `status`='ACTIVE',`updatedAt`=UTC_TIMESTAMP(6),`version`=`version`+1
 WHERE `status`='ONBOARDING';
ALTER TABLE `appUser`
 DROP CHECK `ckAppUser2`,
 ALTER COLUMN `status` SET DEFAULT 'ACTIVE',
 ADD CONSTRAINT `ckAppUser2` CHECK (`status` IN ('ACTIVE','DELETION_PENDING'));

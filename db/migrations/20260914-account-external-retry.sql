-- Apply once after 20260914-grant-issuing-start.sql, before starting the updated app.
-- Existing encrypted leases are honored when first claimed; no private payload is migrated.
ALTER TABLE `deletionJob`
	ADD COLUMN `externalNextAttemptAt` DATETIME(6) NULL COMMENT 'ACCOUNT 외부 정리 재시도/선점 만료 시각; NULL은 즉시 대상' AFTER `dueAt`,
	ADD INDEX `ixDeletionExternalRetry` (`scope`,`status`,`externalNextAttemptAt`,`requestedAt`,`id`);

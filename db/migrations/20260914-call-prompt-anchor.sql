-- Select the existing SafeCall database. Apply once, after 20260913-key-discard-job.sql.
-- Existing calls have no anchor and fail closed on resumption; new calls record one at initial issuance.
ALTER TABLE `callSession`
	ADD COLUMN `promptPreparedAt` DATETIME(6) NULL,
	ADD COLUMN `promptInstructionHash` VARBINARY(32) NULL,
	ADD CONSTRAINT `ckCallPromptAnchor` CHECK ((`promptPreparedAt` IS NULL)=(`promptInstructionHash` IS NULL)),
	ADD CONSTRAINT `ckCallPromptHash` CHECK (`promptInstructionHash` IS NULL OR OCTET_LENGTH(`promptInstructionHash`)=32);

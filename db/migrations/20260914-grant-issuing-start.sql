-- Apply once, before starting the updated application. Existing ISSUING rows with
-- unknown start time fail closed; PENDING rows get a start time when claimed.
ALTER TABLE `connectionGrant`
    MODIFY COLUMN `createdAt` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '발급 작업 대기 시작 시각',
    ADD COLUMN `issuingStartedAt` DATETIME(6) NULL COMMENT '실제 발급 시작 시각; ISSUING 제한 시간 기준' AFTER `createdAt`;

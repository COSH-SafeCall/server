-- Select the existing SafeCall database before applying this additive migration.
-- No key material or provider response is stored here; retain jobs until discard succeeds.
CREATE TABLE IF NOT EXISTS `keyDiscardJob` (
	`id` BINARY(16) NOT NULL,
	`keyRef` TEXT NOT NULL,
	`createdAt` DATETIME(6) NOT NULL,
	`nextAttemptAt` DATETIME(6) NOT NULL,
	PRIMARY KEY (`id`),
	INDEX `ixKeyDiscardDue` (`nextAttemptAt`,`id`)
) ENGINE=InnoDB COMMENT='Durable external key discard jobs, independent of deleted owners';

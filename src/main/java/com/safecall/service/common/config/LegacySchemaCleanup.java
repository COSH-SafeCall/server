package com.safecall.service.common.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacySchemaCleanup implements ApplicationRunner {
	private static final List<String> LEGACY_WEB_SESSION_CHECKS = List.of(
		"ckWebSessionStep", "ckWebSessionGuestStep", "ckWebSessionAnonymousStep",
		"ckWebSessionVersion", "ckWebSessionEntity"
	);

	private final JdbcTemplate jdbc;

	public LegacySchemaCleanup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		boolean legacySchema = tableExists("oauthAttempt")
			|| tableExists("consentEvent")
			|| tableExists("oauthConsent")
			|| tableExists("serviceDocument")
			|| columnExists("appUser", "kakaoSubjectHash")
			|| columnExists("webSession", "sensitiveVerifiedAt")
			|| columnExists("deletionJob", "accountSubjectHash")
			|| columnExists("webSession", "onboardingStep")
			|| columnExists("webSession", "version")
			|| legacyAppUserStatusCheck()
			|| appUserUsesLegacyStatusDefault();
		if (!legacySchema) {
			return;
		}

		dropTable("oauthAttempt");
		dropTable("oauthConsent");
		dropTable("consentEvent");
		dropTable("serviceDocument");
		migrateWebSessions();
		migrateUsers();
		migrateDeletionJobs();
	}

	private void migrateWebSessions() {
		if (!tableExists("webSession")) {
			return;
		}
		LEGACY_WEB_SESSION_CHECKS.forEach(name -> dropCheck("webSession", name));
		jdbc.update("UPDATE `webSession` SET `kind`='MEMBER' WHERE `kind`='KAKAO'");
		dropColumn("webSession", "onboardingStep");
		dropColumn("webSession", "version");
		dropColumn("webSession", "sensitiveVerifiedAt");
		if (!constraintExists("webSession", "ckWebSessionEntity")) {
			jdbc.execute("ALTER TABLE `webSession` ADD CONSTRAINT `ckWebSessionEntity` CHECK (octet_length(`sessionHash`)=32 AND octet_length(`csrfHash`)=32 AND `kind` IN ('ANONYMOUS','GUEST','MEMBER') AND ((`kind`='MEMBER')=(`userId` IS NOT NULL)) AND `status` IN ('ACTIVE','REVOKED','EXPIRED') AND ((`status`='REVOKED')=(`revokedAt` IS NOT NULL)) AND `expiresAt`>`createdAt`)");
		}
	}

	private void migrateUsers() {
		if (!tableExists("appUser")) {
			return;
		}
		jdbc.update("UPDATE `appUser` SET `status`='ACTIVE',`updatedAt`=UTC_TIMESTAMP(6),`version`=`version`+1 WHERE `status`='ONBOARDING'");
		jdbc.update("UPDATE `appUser` SET `genderSource`='USER_CONFIRMED' WHERE `genderSource`='KAKAO'");
		jdbc.update("UPDATE `appUser` SET `birthDateSource`='USER_CONFIRMED' WHERE `birthDateSource`='KAKAO'");
		dropCheck("appUser", "ckAppUserEntity");
		if (legacyAppUserStatusCheck()) {
			dropCheck("appUser", "ckAppUser2");
		}
		if (appUserUsesLegacyStatusDefault()) {
			jdbc.execute("ALTER TABLE `appUser` ALTER COLUMN `status` SET DEFAULT 'ACTIVE'");
		}
		dropIndex("appUser", "uqAppUser1");
		dropColumn("appUser", "kakaoSubjectHash");
		dropColumn("appUser", "kakaoSubjectCipher");
		if (!constraintExists("appUser", "ckAppUser2")) {
			jdbc.execute("ALTER TABLE `appUser` ADD CONSTRAINT `ckAppUser2` CHECK (`status` IN ('ACTIVE','DELETION_PENDING'))");
		}
	}

	private void migrateDeletionJobs() {
		if (!tableExists("deletionJob")) {
			return;
		}
		jdbc.update("DELETE FROM `deletionJob` WHERE `scope` IN ('AI_DATA','LOCATION_DATA')");
		jdbc.update("UPDATE `deletionJob` SET `status`='COMPLETED',`completedAt`=COALESCE(`completedAt`,UTC_TIMESTAMP(6)) WHERE `status`='LOCAL_DELETED'");
		dropCheck("deletionJob", "ckDeletionJobEntity");
		dropIndex("deletionJob", "uqDeletionPendingSubject");
		dropIndex("deletionJob", "ixDeletionExternalRetry");
		dropIndex("deletionJob", "uqDeletionJob2");
		dropColumn("deletionJob", "pendingMarker");
		dropColumn("deletionJob", "accountSubjectHash");
		dropColumn("deletionJob", "cleanupCipher");
		dropColumn("deletionJob", "cleanupKeyRef");
		dropColumn("deletionJob", "externalNextAttemptAt");
		jdbc.execute("ALTER TABLE `deletionJob` ADD COLUMN `pendingMarker` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('PENDING','PROCESSING') THEN 1 ELSE NULL END) STORED");
		jdbc.execute("ALTER TABLE `deletionJob` ADD CONSTRAINT `uqDeletionJob2` UNIQUE (`userId`,`scope`,`pendingMarker`)");
	}

	private void dropTable(String table) {
		if (tableExists(table)) {
			jdbc.execute("DROP TABLE `" + table + "`");
		}
	}

	private void dropColumn(String table, String column) {
		if (columnExists(table, column)) {
			jdbc.execute("ALTER TABLE `" + table + "` DROP COLUMN `" + column + "`");
		}
	}

	private void dropCheck(String table, String constraint) {
		if (constraintExists(table, constraint)) {
			jdbc.execute("ALTER TABLE `" + table + "` DROP CHECK `" + constraint + "`");
		}
	}

	private void dropIndex(String table, String index) {
		if (indexExists(table, index)) {
			jdbc.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
		}
	}

	private boolean tableExists(String table) {
		return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", table) > 0;
	}

	private boolean columnExists(String table, String column) {
		return count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", table, column) > 0;
	}

	private boolean constraintExists(String table, String constraint) {
		return count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name=? AND constraint_name=?", table, constraint) > 0;
	}

	private boolean indexExists(String table, String index) {
		return count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?", table, index) > 0;
	}

	private boolean legacyAppUserStatusCheck() {
		return count("SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ckAppUser2' AND check_clause LIKE '%ONBOARDING%'") > 0;
	}

	private boolean appUserUsesLegacyStatusDefault() {
		if (!columnExists("appUser", "status")) {
			return false;
		}
		String value = jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='appUser' AND column_name='status'", String.class);
		return !"ACTIVE".equals(value);
	}

	private int count(String sql, Object... arguments) {
		return jdbc.queryForObject(sql, Integer.class, arguments);
	}
}

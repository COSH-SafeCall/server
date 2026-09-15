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
	private static final List<String> ONBOARDING_CHECKS = List.of(
		"ckWebSessionStep",
		"ckWebSessionGuestStep",
		"ckWebSessionAnonymousStep",
		"ckWebSessionVersion"
	);

	private final JdbcTemplate jdbc;

	public LegacySchemaCleanup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		boolean legacySchema = tableExists("oauthConsent")
			|| tableExists("serviceDocument")
			|| columnExists("webSession", "onboardingStep")
			|| columnExists("webSession", "version")
			|| legacyAppUserStatusCheck()
			|| appUserUsesLegacyStatusDefault();
		if (!legacySchema) {
			return;
		}

		if (tableExists("oauthAttempt")) {
			jdbc.update("UPDATE `oauthAttempt` SET `status`='EXPIRED',`completedAt`=UTC_TIMESTAMP(6) WHERE `status` IN ('PENDING','EXCHANGING')");
		}
		dropTable("oauthConsent");
		dropForeignKey("consentEvent", "fkConsentEvent2");
		dropTable("serviceDocument");
		if (tableExists("webSession")) {
			ONBOARDING_CHECKS.forEach(name -> dropCheck("webSession", name));
			dropColumn("webSession", "onboardingStep");
			dropColumn("webSession", "version");
		}
		if (tableExists("appUser")) {
			jdbc.update("UPDATE `appUser` SET `status`='ACTIVE',`updatedAt`=UTC_TIMESTAMP(6),`version`=`version`+1 WHERE `status`='ONBOARDING'");
			if (legacyAppUserStatusCheck()) {
				dropCheck("appUser", "ckAppUser2");
			}
			if (appUserUsesLegacyStatusDefault()) {
				jdbc.execute("ALTER TABLE `appUser` ALTER COLUMN `status` SET DEFAULT 'ACTIVE'");
			}
			if (!constraintExists("appUser", "ckAppUser2")) {
				jdbc.execute("ALTER TABLE `appUser` ADD CONSTRAINT `ckAppUser2` CHECK (`status` IN ('ACTIVE','DELETION_PENDING'))");
			}
		}
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

	private void dropForeignKey(String table, String constraint) {
		if (constraintExists(table, constraint)) {
			jdbc.execute("ALTER TABLE `" + table + "` DROP FOREIGN KEY `" + constraint + "`");
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

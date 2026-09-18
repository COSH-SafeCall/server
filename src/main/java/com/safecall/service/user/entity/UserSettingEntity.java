package com.safecall.service.user.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@org.hibernate.annotations.Check(name = "ckUserSettingEntity", constraints = "`incomingAlertMode` IN ('RINGTONE','SILENT') AND `microphonePermission` IN ('GRANTED','DENIED','NOT_DETERMINED') AND `locationPermission` IN ('GRANTED','DENIED','NOT_DETERMINED') AND `version`>0")
@Entity
@Table(name = "userSetting")
public class UserSettingEntity {
	@Id
	@Column(name = "userId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID userId;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "userId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkUserSetting1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private AppUserEntity user;

	@Column(name = "incomingAlertMode", nullable = false, length = 8, columnDefinition = "VARCHAR(8) NOT NULL DEFAULT 'RINGTONE'")
	private String incomingAlertMode;

	@Column(name = "microphonePermission", nullable = false, length = 16, columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'NOT_DETERMINED'")
	private String microphonePermission;

	@Column(name = "locationPermission", nullable = false, length = 16, columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'NOT_DETERMINED'")
	private String locationPermission;

	@Column(name = "updatedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 1")
	private Long version;

	protected UserSettingEntity() {}
}

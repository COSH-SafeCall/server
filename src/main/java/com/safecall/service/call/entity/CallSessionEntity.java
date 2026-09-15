package com.safecall.service.call.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import com.safecall.service.auth.entity.WebSessionEntity;

@org.hibernate.annotations.Check(name = "ckCallSessionEntity", constraints = "`state` IN ('CREATED','PREPARING','RINGING','ACTIVE','ENDED','FAILED') AND (`endReason` IS NULL OR `endReason` IN ('USER_ENDED','DECLINED','BACK_NAVIGATION','TAB_HIDDEN','PAGE_EXIT','PAGE_RELOAD','SWITCH_TO_FALLBACK','CONNECTION_FAILED','RINGING_FAILED','MICROPHONE_FAILED','AUDIO_FAILED','CONNECTION_LOST','SESSION_EXPIRED','DURATION_LIMIT','LOGOUT','CONSENT_WITHDRAWN','DATA_DELETION')) AND `version`>0 AND ((`state` IN ('ENDED','FAILED'))=(`endedAt` IS NOT NULL)) AND ((`state` IN ('ENDED','FAILED'))=(`endReason` IS NOT NULL)) AND (`answeredAt` IS NULL OR (`ringingAt` IS NOT NULL AND `answeredAt`>=`ringingAt`)) AND (`state`<>'ACTIVE' OR `answeredAt` IS NOT NULL) AND (`state`<>'RINGING' OR `ringingAt` IS NOT NULL) AND `expiresAt`>`createdAt` AND `startMode` IN ('STANDARD','QUICK') AND (`startMode`<>'QUICK' OR `counterpartCode`='FATHER') AND octet_length(`pageKeyHash`)=32 AND `leaseExpiresAt`>`lastHeartbeatAt` AND `lastHeartbeatAt`>=`createdAt` AND (`ringingAt` IS NULL OR `ringingAt`>=`createdAt`) AND (`endedAt` IS NULL OR ((`ringingAt` IS NULL OR `endedAt`>=`ringingAt`) AND (`answeredAt` IS NULL OR `endedAt`>=`answeredAt`)))")
@Entity
@Table(name = "callSession", uniqueConstraints = {
	@UniqueConstraint(name = "uqCallSession1", columnNames = {"id", "sessionId"}),
	@UniqueConstraint(name = "uqCallSession2", columnNames = {"sessionId", "activeMarker"}),
	@UniqueConstraint(name = "uqCallClientId", columnNames = {"sessionId", "clientCallId"})
}, indexes = {
	@Index(name = "ixCallHistory", columnList = "sessionId,createdAt DESC,id DESC"),
	@Index(name = "ixCallReaper", columnList = "state,lastHeartbeatAt"),
	@Index(name = "ixCallLease", columnList = "state,leaseExpiresAt")
})
public class CallSessionEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "sessionId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID sessionId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sessionId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkCallSession1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private WebSessionEntity session;

	@Column(name = "pageKeyHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] pageKeyHash;

	@Column(name = "clientCallId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID clientCallId;

	@Column(name = "startMode", nullable = false, length = 8, columnDefinition = "VARCHAR(8) NOT NULL DEFAULT 'STANDARD'")
	private String startMode;

	@Column(name = "releaseId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID releaseId;

	@Column(name = "scenarioCode", nullable = false, length = 24)
	private String scenarioCode;

	@Column(name = "counterpartCode", nullable = false, length = 6)
	private String counterpartCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "releaseId", referencedColumnName = "releaseId", insertable = false, updatable = false),
		@JoinColumn(name = "scenarioCode", referencedColumnName = "scenarioCode", insertable = false, updatable = false),
		@JoinColumn(name = "counterpartCode", referencedColumnName = "counterpartCode", insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fkCallSession2"))
	private PersonaPromptEntity prompt;

	@Column(name = "state", nullable = false, length = 20, columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'CREATED'")
	private String state;

	@Column(name = "endReason", length = 32)
	private String endReason;

	@Column(name = "isDemographicApplied", nullable = false, columnDefinition = "TINYINT(1)")
	private Boolean demographicApplied;

	@Column(name = "isGenderAddressApplied", nullable = false, columnDefinition = "TINYINT(1)")
	private Boolean genderAddressApplied;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "ringingAt", columnDefinition = "DATETIME(6)")
	private Instant ringingAt;

	@Column(name = "answeredAt", columnDefinition = "DATETIME(6)")
	private Instant answeredAt;

	@Column(name = "endedAt", columnDefinition = "DATETIME(6)")
	private Instant endedAt;

	@Column(name = "lastHeartbeatAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant lastHeartbeatAt;

	@Column(name = "leaseExpiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant leaseExpiresAt;

	@Column(name = "expiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	@Column(name = "policyVersion", nullable = false, length = 32, columnDefinition = "VARCHAR(32) NOT NULL DEFAULT 'mvp-2026-09-11'")
	private String policyVersion;

	@Version
	@Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 1")
	private Long version;

	@Column(name = "activeMarker", insertable = false, updatable = false,
		columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN state IN ('CREATED','PREPARING','RINGING','ACTIVE') THEN 1 ELSE NULL END) STORED")
	private Byte activeMarker;

	protected CallSessionEntity() {}
}

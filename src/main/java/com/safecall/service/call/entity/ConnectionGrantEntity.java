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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@org.hibernate.annotations.Check(name = "ckConnectionGrantEntity", constraints = "`status` IN ('PENDING','ISSUING','READY','USED','INVALIDATED','UNKNOWN') AND (`status`<>'READY' OR (`tokenCipher` IS NOT NULL AND `newSessionExpiresAt` IS NOT NULL AND `expiresAt` IS NOT NULL)) AND (`status` NOT IN ('USED','INVALIDATED','UNKNOWN') OR `tokenCipher` IS NULL) AND (`expiresAt` IS NULL OR `newSessionExpiresAt`<=`expiresAt`) AND (`status`<>'READY' OR (`issuedAt` IS NOT NULL AND `newSessionExpiresAt`>`issuedAt` AND `newSessionExpiresAt`<=DATE_ADD(`issuedAt`, INTERVAL 60 SECOND))) AND (`status`<>'USED' OR `usedAt` IS NOT NULL) AND `purpose`='INITIAL' AND `generation`=1 AND ((`status`='READY')=(`tokenCipher` IS NOT NULL)) AND ((`tokenCipher` IS NULL)=(`keyRef` IS NULL))")
@Entity
@Table(name = "connectionGrant", uniqueConstraints = {
	@UniqueConstraint(name = "uqGrantGeneration", columnNames = {"callId", "generation"}),
	@UniqueConstraint(name = "uqGrantOpen", columnNames = {"callId", "openMarker"}),
	@UniqueConstraint(name = "uqGrantInitial", columnNames = {"callId", "initialMarker"})
}, indexes = @Index(name = "ixGrantExpiry", columnList = "status,newSessionExpiresAt"))
public class ConnectionGrantEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "callId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID callId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "callId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkConnectionGrant1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private CallSessionEntity call;

	@Column(name = "generation", nullable = false)
	private Integer generation;

	@Column(name = "purpose", nullable = false, length = 7)
	private String purpose;

	@Column(name = "keyRef", columnDefinition = "TEXT")
	private String keyRef;

	@Column(name = "status", nullable = false, length = 12)
	private String status;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "issuingStartedAt", columnDefinition = "DATETIME(6)")
	private Instant issuingStartedAt;

	@Column(name = "tokenCipher", columnDefinition = "BLOB")
	private byte[] tokenCipher;

	@Column(name = "newSessionExpiresAt", columnDefinition = "DATETIME(6)")
	private Instant newSessionExpiresAt;

	@Column(name = "expiresAt", columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	@Column(name = "issuedAt", columnDefinition = "DATETIME(6)")
	private Instant issuedAt;

	@Column(name = "usedAt", columnDefinition = "DATETIME(6)")
	private Instant usedAt;

	@Column(name = "openMarker", insertable = false, updatable = false,
		columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','ISSUING','READY') THEN 1 ELSE NULL END) STORED")
	private Byte openMarker;

	@Column(name = "initialMarker", insertable = false, updatable = false,
		columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN purpose='INITIAL' THEN 1 ELSE NULL END) STORED")
	private Byte initialMarker;

	protected ConnectionGrantEntity() {}
}

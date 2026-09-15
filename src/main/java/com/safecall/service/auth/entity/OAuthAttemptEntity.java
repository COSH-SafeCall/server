package com.safecall.service.auth.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.Check;

@Check(name = "ckOauthAttemptEntity", constraints = "`purpose` IN ('LOGIN','REAUTH') AND octet_length(`stateHash`)=32 AND `status` IN ('PENDING','EXCHANGING','SUCCEEDED','FAILED','EXPIRED') AND `expiresAt`>`createdAt` AND (`completedAt` IS NULL OR `completedAt`>=`createdAt`) AND ((`status` IN ('SUCCEEDED','FAILED','EXPIRED'))=(`completedAt` IS NOT NULL))")
@Entity
@Table(name = "oauthAttempt",
	uniqueConstraints = @UniqueConstraint(name = "uqOauthAttemptState", columnNames = "stateHash"),
	indexes = @Index(name = "ixOauthAttemptExpiry", columnList = "status,expiresAt"))
public class OAuthAttemptEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "sessionId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID sessionId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sessionId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkOauthAttemptSession"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private WebSessionEntity session;

	@Column(name = "stateHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] stateHash;

	@Column(name = "purpose", nullable = false, length = 6, columnDefinition = "VARCHAR(6) NOT NULL DEFAULT 'LOGIN'")
	private String purpose;

	@Column(name = "status", nullable = false, length = 10, columnDefinition = "VARCHAR(10) NOT NULL DEFAULT 'PENDING'")
	private String status;

	@Column(name = "redirectUri", nullable = false, length = 512)
	private String redirectUri;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "expiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	@Column(name = "completedAt", columnDefinition = "DATETIME(6)")
	private Instant completedAt;

	protected OAuthAttemptEntity() {}
}

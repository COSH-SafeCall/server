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
import com.safecall.service.user.entity.AppUserEntity;
import org.hibernate.annotations.Check;

@Check(name = "ckWebSessionEntity", constraints = "octet_length(`sessionHash`)=32 AND octet_length(`csrfHash`)=32 AND `kind` IN ('ANONYMOUS','GUEST','MEMBER') AND ((`kind`='MEMBER')=(`userId` IS NOT NULL)) AND `status` IN ('ACTIVE','REVOKED','EXPIRED') AND ((`status`='REVOKED')=(`revokedAt` IS NOT NULL)) AND `expiresAt`>`createdAt` AND (`kind`<>'GUEST' OR `expiresAt`<=DATE_ADD(`createdAt`, INTERVAL 24 HOUR))")
@Entity
@Table(name = "webSession",
	uniqueConstraints = @UniqueConstraint(name = "uqWebSessionHash", columnNames = "sessionHash"),
	indexes = {
		@Index(name = "ixWebSessionUser", columnList = "userId"),
		@Index(name = "ixWebSessionExpiry", columnList = "status,expiresAt")
	})
public class WebSessionEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "sessionHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] sessionHash;

	@Column(name = "csrfHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] csrfHash;

	@Column(name = "userId", columnDefinition = "BINARY(16)")
	private UUID userId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "userId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkWebSessionUser"))
	private AppUserEntity user;

	@Column(name = "kind", nullable = false, length = 9)
	private String kind;

	@Column(name = "status", nullable = false, length = 7, columnDefinition = "VARCHAR(7) NOT NULL DEFAULT 'ACTIVE'")
	private String status;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "expiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	@Column(name = "revokedAt", columnDefinition = "DATETIME(6)")
	private Instant revokedAt;

	protected WebSessionEntity() {}
}

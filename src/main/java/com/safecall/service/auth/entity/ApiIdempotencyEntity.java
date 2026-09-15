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
import com.safecall.service.user.entity.AppUserEntity;
import org.hibernate.annotations.Check;

@Check(name = "ckApiIdempotencyEntity", constraints = "octet_length(`scopeHash`)=32 AND octet_length(`requestHash`)=32 AND `status` IN ('IN_PROGRESS','DONE','UNKNOWN') AND `expiresAt`>`createdAt`")
@Entity
@Table(name = "apiIdempotency",
	uniqueConstraints = @UniqueConstraint(name = "uqApiIdempotency1", columnNames = {"scopeHash", "operation", "requestKey"}),
	indexes = {
		@Index(name = "ixIdempotencyExpiry", columnList = "expiresAt"),
		@Index(name = "ixIdempotencyUser", columnList = "ownerUserId"),
		@Index(name = "ixIdempotencySession", columnList = "ownerSessionId")
	})
public class ApiIdempotencyEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "ownerUserId", columnDefinition = "BINARY(16)")
	private UUID ownerUserId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ownerUserId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkApiIdempotency1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private AppUserEntity ownerUser;

	@Column(name = "ownerSessionId", columnDefinition = "BINARY(16)")
	private UUID ownerSessionId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ownerSessionId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkApiIdempotency2"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private WebSessionEntity ownerSession;

	@Column(name = "scopeHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] scopeHash;

	@Column(name = "operation", nullable = false, length = 64)
	private String operation;

	@Column(name = "requestKey", nullable = false, columnDefinition = "BINARY(16)")
	private UUID requestKey;

	@Column(name = "requestHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] requestHash;

	@Column(name = "status", nullable = false, length = 11)
	private String status;

	@Column(name = "resourceId", columnDefinition = "BINARY(16)")
	private UUID resourceId;

	@Column(name = "responseCipher", columnDefinition = "BLOB")
	private byte[] responseCipher;

	@Column(name = "responseExpiresAt", columnDefinition = "DATETIME(6)")
	private Instant responseExpiresAt;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "expiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	protected ApiIdempotencyEntity() {}
}

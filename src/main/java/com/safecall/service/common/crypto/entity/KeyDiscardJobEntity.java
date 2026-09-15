package com.safecall.service.common.crypto.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "keyDiscardJob", indexes = @Index(name = "ixKeyDiscardDue", columnList = "nextAttemptAt,id"))
public class KeyDiscardJobEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "keyRef", nullable = false, columnDefinition = "TEXT")
	private String keyRef;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant createdAt;

	@Column(name = "nextAttemptAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant nextAttemptAt;

	protected KeyDiscardJobEntity() {}
}

package com.safecall.service.user.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@org.hibernate.annotations.Check(name = "ckEmergencyContactEntity", constraints = "`slot` IN (1,2) AND octet_length(`phoneHash`)=32 AND `version`>0")
@Entity
@Table(name = "emergencyContact", uniqueConstraints = {
	@UniqueConstraint(name = "uqEmergencyContact1", columnNames = {"userId", "slot"}),
	@UniqueConstraint(name = "uqEmergencyContact2", columnNames = {"userId", "phoneHash"}),
	@UniqueConstraint(name = "uqEmergencyContact3", columnNames = {"id", "userId"})
})
public class EmergencyContactEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "userId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "userId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkEmergencyContact1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private AppUserEntity user;

	@Column(name = "slot", nullable = false)
	private Short slot;

	@Column(name = "nameCipher", nullable = false, columnDefinition = "BLOB")
	private byte[] nameCipher;

	@Column(name = "relationshipCipher", nullable = false, columnDefinition = "BLOB")
	private byte[] relationshipCipher;

	@Column(name = "phoneCipher", nullable = false, columnDefinition = "BLOB")
	private byte[] phoneCipher;

	@Column(name = "phoneHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] phoneHash;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "updatedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 1")
	private Long version;

	protected EmergencyContactEntity() {}
}

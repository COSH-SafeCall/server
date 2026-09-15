package com.safecall.service.user.entity;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@org.hibernate.annotations.Check(name = "ckConsentEventEntity", constraints = "`action` IN ('GRANTED','DECLINED','WITHDRAWN') AND `documentCode` IN ('PRIVACY_PROCESSING','AI_CALL','LOCATION_PROCESSING') AND `documentVersion`=1")
@Entity
@Table(name = "consentEvent", indexes = @Index(name = "ixConsentLatest", columnList = "userId,documentCode,recordedAt DESC,id DESC"))
public class ConsentEventEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "userId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "userId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkConsentEvent1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private AppUserEntity user;

	@Column(name = "documentCode", nullable = false, length = 24)
	private String documentCode;

	@Column(name = "documentVersion", nullable = false)
	private Integer documentVersion;

	@Column(name = "action", nullable = false, length = 9)
	private String action;

	@Column(name = "recordedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant recordedAt;

	protected ConsentEventEntity() {}
}

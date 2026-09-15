package com.safecall.service.call.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@org.hibernate.annotations.Check(name = "ckPromptReleaseEntity", constraints = "`version`>0 AND `status` IN ('DRAFT','PUBLISHED','RETIRED') AND (`status`='DRAFT' OR (`publishedAt` IS NOT NULL AND `validationRef` IS NOT NULL))")
@Entity
@Table(name = "promptRelease", uniqueConstraints = {
	@UniqueConstraint(name = "uqPromptRelease1", columnNames = "version"),
	@UniqueConstraint(name = "uqPromptRelease2", columnNames = "publishedMarker")
})
public class PromptReleaseEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "version", nullable = false)
	private Integer version;

	@Column(name = "safetyInstruction", nullable = false, columnDefinition = "TEXT")
	private String safetyInstruction;

	@Column(name = "demographicRules", nullable = false, columnDefinition = "TEXT")
	private String demographicRules;

	@Column(name = "guestInstruction", nullable = false, columnDefinition = "TEXT")
	private String guestInstruction;

	@Column(name = "modelId", nullable = false, columnDefinition = "TEXT")
	private String modelId;

	@Column(name = "apiVersion", nullable = false, columnDefinition = "TEXT")
	private String apiVersion;

	@Column(name = "status", nullable = false, length = 9, columnDefinition = "VARCHAR(9) NOT NULL DEFAULT 'DRAFT'")
	private String status;

	@Column(name = "validationRef", columnDefinition = "TEXT")
	private String validationRef;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "publishedAt", columnDefinition = "DATETIME(6)")
	private Instant publishedAt;

	@Column(name = "publishedMarker", insertable = false, updatable = false,
		columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED")
	private Byte publishedMarker;

	protected PromptReleaseEntity() {}
}

package com.safecall.service.history.entity;

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
import com.safecall.service.user.entity.AppUserEntity;

@org.hibernate.annotations.Check(name = "ckDeletionJobEntity", constraints = "`scope` IN ('ACCOUNT','USAGE_HISTORY') AND `status` IN ('PENDING','PROCESSING','COMPLETED','FAILED') AND octet_length(`receiptHash`)=32 AND ((`status`='COMPLETED')=(`completedAt` IS NOT NULL)) AND `dueAt`>=`requestedAt` AND `receiptExpiresAt`>`requestedAt` AND (`completedAt` IS NULL OR `completedAt`>=`requestedAt`)")
@Entity
@Table(name = "deletionJob", uniqueConstraints = {
	@UniqueConstraint(name = "uqDeletionJob1", columnNames = "receiptHash"),
	@UniqueConstraint(name = "uqDeletionJob2", columnNames = {"userId", "scope", "pendingMarker"})
}, indexes = {
	@Index(name = "ixDeletionDue", columnList = "status,dueAt")
})
public class DeletionJobEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "userId", columnDefinition = "BINARY(16)")
	private UUID userId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "userId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkDeletionJob1"))
	@OnDelete(action = OnDeleteAction.SET_NULL)
	private AppUserEntity user;

	@Column(name = "scope", nullable = false, length = 16)
	private String scope;

	@Column(name = "status", nullable = false, length = 13, columnDefinition = "VARCHAR(13) NOT NULL DEFAULT 'PENDING'")
	private String status;

	@Column(name = "receiptHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] receiptHash;

	@Column(name = "requestedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant requestedAt;

	@Column(name = "cutoffAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant cutoffAt;

	@Column(name = "dueAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant dueAt;

	@Column(name = "completedAt", columnDefinition = "DATETIME(6)")
	private Instant completedAt;

	@Column(name = "errorCode", length = 40)
	private String errorCode;

	@Column(name = "receiptExpiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant receiptExpiresAt;

	@Column(name = "pendingMarker", insertable = false, updatable = false,
		columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PROCESSING') THEN 1 ELSE NULL END) STORED")
	private Byte pendingMarker;

	protected DeletionJobEntity() {}
}

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

@org.hibernate.annotations.Check(name = "ckDeletionJobEntity", constraints = "`scope` IN ('ACCOUNT','USAGE_HISTORY','AI_DATA','LOCATION_DATA') AND `status` IN ('PENDING','PROCESSING','LOCAL_DELETED','COMPLETED','FAILED') AND octet_length(`receiptHash`)=32 AND ((`status`='COMPLETED')=(`completedAt` IS NOT NULL)) AND (`accountSubjectHash` IS NULL OR octet_length(`accountSubjectHash`)=32) AND (`accountSubjectHash` IS NULL OR `scope`='ACCOUNT') AND (`scope`<>'ACCOUNT' OR `status` NOT IN ('PENDING','PROCESSING','LOCAL_DELETED') OR `accountSubjectHash` IS NOT NULL) AND (`status`<>'COMPLETED' OR `accountSubjectHash` IS NULL) AND ((`cleanupCipher` IS NULL)=(`cleanupKeyRef` IS NULL)) AND (`status`<>'COMPLETED' OR `cleanupCipher` IS NULL) AND `dueAt`>=`requestedAt` AND `receiptExpiresAt`>`requestedAt` AND (`completedAt` IS NULL OR `completedAt`>=`requestedAt`)")
@Entity
@Table(name = "deletionJob", uniqueConstraints = {
	@UniqueConstraint(name = "uqDeletionJob1", columnNames = "receiptHash"),
	@UniqueConstraint(name = "uqDeletionJob2", columnNames = {"userId", "scope", "pendingMarker"}),
	@UniqueConstraint(name = "uqDeletionPendingSubject", columnNames = {"accountSubjectHash", "pendingMarker"})
}, indexes = {
	@Index(name = "ixDeletionDue", columnList = "status,dueAt"),
	@Index(name = "ixDeletionExternalRetry", columnList = "scope,status,externalNextAttemptAt,requestedAt,id")
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

	@Column(name = "accountSubjectHash", columnDefinition = "VARBINARY(32)")
	private byte[] accountSubjectHash;

	@Column(name = "status", nullable = false, length = 13, columnDefinition = "VARCHAR(13) NOT NULL DEFAULT 'PENDING'")
	private String status;

	@Column(name = "receiptHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] receiptHash;

	@Column(name = "cleanupCipher", columnDefinition = "BLOB")
	private byte[] cleanupCipher;

	@Column(name = "cleanupKeyRef", columnDefinition = "TEXT")
	private String cleanupKeyRef;

	@Column(name = "requestedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant requestedAt;

	@Column(name = "cutoffAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant cutoffAt;

	@Column(name = "dueAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant dueAt;

	@Column(name = "externalNextAttemptAt", columnDefinition = "DATETIME(6)")
	private Instant externalNextAttemptAt;

	@Column(name = "completedAt", columnDefinition = "DATETIME(6)")
	private Instant completedAt;

	@Column(name = "errorCode", length = 40)
	private String errorCode;

	@Column(name = "receiptExpiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant receiptExpiresAt;

	@Column(name = "pendingMarker", insertable = false, updatable = false,
		columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PROCESSING','LOCAL_DELETED') THEN 1 ELSE NULL END) STORED")
	private Byte pendingMarker;

	protected DeletionJobEntity() {}
}

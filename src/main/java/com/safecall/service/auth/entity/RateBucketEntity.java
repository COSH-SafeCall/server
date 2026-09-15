package com.safecall.service.auth.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

@Check(name = "ckRateBucketEntity", constraints = "`scopeKind` IN ('USER','SESSION','IP') AND octet_length(`scopeHash`)=32 AND `windowSeconds`>0 AND `usedCount`>=0")
@Entity
@IdClass(RateBucketEntity.PrimaryKey.class)
@Table(name = "rateBucket", indexes = @Index(name = "ixRateExpiry", columnList = "expiresAt"))
public class RateBucketEntity {
	@jakarta.persistence.Id
	@Column(name = "scopeKind", nullable = false, length = 12)
	private String scopeKind;

	@jakarta.persistence.Id
	@Column(name = "scopeHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] scopeHash;

	@jakarta.persistence.Id
	@Column(name = "operation", nullable = false, length = 24)
	private String operation;

	@jakarta.persistence.Id
	@Column(name = "windowStart", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant windowStart;

	@jakarta.persistence.Id
	@Column(name = "windowSeconds", nullable = false)
	private Integer windowSeconds;

	@Column(name = "usedCount", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0")
	private Integer usedCount;

	@Column(name = "expiresAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	protected RateBucketEntity() {}

	public static class PrimaryKey implements Serializable {
		private String scopeKind;
		private byte[] scopeHash;
		private String operation;
		private Instant windowStart;
		private Integer windowSeconds;

		public PrimaryKey() {}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof PrimaryKey other)) return false;
			return Objects.equals(scopeKind, other.scopeKind)
				&& Arrays.equals(scopeHash, other.scopeHash)
				&& Objects.equals(operation, other.operation)
				&& Objects.equals(windowStart, other.windowStart)
				&& Objects.equals(windowSeconds, other.windowSeconds);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(scopeKind, operation, windowStart, windowSeconds);
			return 31 * result + Arrays.hashCode(scopeHash);
		}
	}
}

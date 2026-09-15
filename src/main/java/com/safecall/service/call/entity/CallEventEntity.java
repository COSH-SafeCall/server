package com.safecall.service.call.entity;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@org.hibernate.annotations.Check(name = "ckCallEventEntity", constraints = "`sequence`>0 AND `eventType` IN ('CREATED','PREPARING','CONNECTED','RINGING_SHOWN','ANSWERED','ENDED','FAILED') AND `stateAfter` IN ('CREATED','PREPARING','RINGING','ACTIVE','ENDED','FAILED') AND octet_length(`requestHash`)=32")
@Entity
@Table(name = "callEvent", uniqueConstraints = {
	@UniqueConstraint(name = "uqCallEvent1", columnNames = {"callId", "sequence"}),
	@UniqueConstraint(name = "uqCallEvent2", columnNames = {"callId", "eventKey"})
})
public class CallEventEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "callId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID callId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "callId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkCallEvent1"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private CallSessionEntity call;

	@Column(name = "sequence", nullable = false)
	private Long sequence;

	@Column(name = "eventKey", nullable = false, columnDefinition = "BINARY(16)")
	private UUID eventKey;

	@Column(name = "requestHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] requestHash;

	@Column(name = "eventType", nullable = false, length = 32)
	private String eventType;

	@Column(name = "stateAfter", nullable = false, length = 20)
	private String stateAfter;

	@Column(name = "occurredAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant occurredAt;

	@Column(name = "recordedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant recordedAt;

	protected CallEventEntity() {}
}

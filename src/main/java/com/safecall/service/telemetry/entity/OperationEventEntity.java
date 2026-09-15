package com.safecall.service.telemetry.entity;

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
import com.safecall.service.auth.entity.WebSessionEntity;
import com.safecall.service.call.entity.CallSessionEntity;

@org.hibernate.annotations.Check(name = "ckOperationEventEntity", constraints = "`category` IN ('AUTH','PERMISSION','CALL','AUDIO','GESTURE','LOCATION','MESSAGE_COMPOSER','SOS','FALLBACK') AND (`isSuccess` IS NULL OR `isSuccess` IN (0,1)) AND (`latencyMs` IS NULL OR `latencyMs`>=0) AND (`networkType` IS NULL OR `networkType` IN ('WIFI','CELLULAR','OFFLINE','UNKNOWN')) AND `code` IN ('AUTH_SUCCEEDED','MICROPHONE_PERMISSION_REVIEWED','LOCATION_PERMISSION_REVIEWED','LIVE_CONNECT_STARTED','LIVE_CONNECT_SUCCEEDED','LIVE_CONNECT_FAILED','RINGING_SHOWN','RINGING_FAILED','FIRST_AUDIO_PLAYED','AUDIO_INTERRUPTED','PAGE_EXITED','QUICK_START_SELECTED','QUICK_START_CANCELLED','LOCATION_AVAILABLE','LOCATION_UNAVAILABLE','COMPOSER_OPENED','COMPOSER_OPEN_FAILED','SOS_GUIDE_VIEWED','SOS_GUIDE_FAILED','FALLBACK_STARTED','FALLBACK_ENDED','PERMISSION_QUERY_UNAVAILABLE','PAGE_RELOADED')")
@Entity
@Table(name = "operationEvent",
	uniqueConstraints = @UniqueConstraint(name = "uqOperationEvent1", columnNames = {"sessionId", "eventKey"}),
	indexes = {
		@Index(name = "ixOperationTime", columnList = "recordedAt"),
		@Index(name = "ixOperationCall", columnList = "callId")
	})
public class OperationEventEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "sessionId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID sessionId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sessionId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkOperationEvent2"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private WebSessionEntity session;

	@Column(name = "callId", columnDefinition = "BINARY(16)")
	private UUID callId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "callId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkOperationEvent3"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private CallSessionEntity call;

	@Column(name = "eventKey", nullable = false, columnDefinition = "BINARY(16)")
	private UUID eventKey;

	@Column(name = "category", nullable = false, length = 16)
	private String category;

	@Column(name = "code", nullable = false, length = 48)
	private String code;

	@Column(name = "isSuccess", columnDefinition = "TINYINT(1)")
	private Boolean success;

	@Column(name = "latencyMs")
	private Integer latencyMs;

	@Column(name = "networkType", length = 8)
	private String networkType;

	@Column(name = "webVersion", length = 40)
	private String webVersion;

	@Column(name = "occurredAt", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant occurredAt;

	@Column(name = "recordedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant recordedAt;

	protected OperationEventEntity() {}
}

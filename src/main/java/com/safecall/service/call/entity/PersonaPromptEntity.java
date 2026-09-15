package com.safecall.service.call.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@IdClass(PersonaPromptEntity.PrimaryKey.class)
@Table(name = "personaPrompt")
public class PersonaPromptEntity {
	@jakarta.persistence.Id
	@Column(name = "releaseId", nullable = false, columnDefinition = "BINARY(16)")
	private UUID releaseId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "releaseId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkPersonaPrompt1"))
	private PromptReleaseEntity release;

	@jakarta.persistence.Id
	@Column(name = "scenarioCode", nullable = false, length = 24)
	private String scenarioCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "scenarioCode", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkPersonaPrompt2"))
	private ScenarioEntity scenario;

	@jakarta.persistence.Id
	@Column(name = "counterpartCode", nullable = false, length = 6)
	private String counterpartCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "counterpartCode", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fkPersonaPrompt3"))
	private CounterpartEntity counterpart;

	@Column(name = "baseInstruction", nullable = false, columnDefinition = "TEXT")
	private String baseInstruction;

	@Column(name = "voiceId", nullable = false, columnDefinition = "TEXT")
	private String voiceId;

	protected PersonaPromptEntity() {}

	public static class PrimaryKey implements Serializable {
		private UUID releaseId;
		private String scenarioCode;
		private String counterpartCode;

		public PrimaryKey() {}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof PrimaryKey other)) return false;
			return Objects.equals(releaseId, other.releaseId)
				&& Objects.equals(scenarioCode, other.scenarioCode)
				&& Objects.equals(counterpartCode, other.counterpartCode);
		}

		@Override
		public int hashCode() {
			return Objects.hash(releaseId, scenarioCode, counterpartCode);
		}
	}
}

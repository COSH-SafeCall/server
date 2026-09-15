package com.safecall.service.call.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@org.hibernate.annotations.Check(name = "ckScenarioEntity", constraints = "`code` IN ('FOLLOWED','UNSAFE_TAXI','STRANGER_NEARBY','WALKING_ALONE') AND `sortOrder` BETWEEN 1 AND 4")
@Entity
@Table(name = "scenario", uniqueConstraints = @UniqueConstraint(name = "uqScenario1", columnNames = "sortOrder"))
public class ScenarioEntity {
	@Id
	@Column(name = "code", nullable = false, length = 24)
	private String code;

	@Column(name = "label", nullable = false, columnDefinition = "TEXT")
	private String label;

	@Column(name = "sortOrder", nullable = false)
	private Short sortOrder;

	protected ScenarioEntity() {}
}

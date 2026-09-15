package com.safecall.service.call.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@org.hibernate.annotations.Check(name = "ckCounterpartEntity", constraints = "`code` IN ('FATHER','MOTHER','FRIEND') AND `sortOrder` BETWEEN 1 AND 3")
@Entity
@Table(name = "counterpart", uniqueConstraints = @UniqueConstraint(name = "uqCounterpart1", columnNames = "sortOrder"))
public class CounterpartEntity {
	@Id
	@Column(name = "code", nullable = false, length = 6)
	private String code;

	@Column(name = "label", nullable = false, columnDefinition = "TEXT")
	private String label;

	@Column(name = "displayName", nullable = false, columnDefinition = "TEXT")
	private String displayName;

	@Column(name = "sortOrder", nullable = false)
	private Short sortOrder;

	protected CounterpartEntity() {}
}

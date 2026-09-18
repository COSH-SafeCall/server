package com.safecall.service.call.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

@Check(name = "ckCallCapacityLockEntity", constraints = "`id`=1")
@Entity
@Table(name = "callCapacityLock")
public class CallCapacityLockEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "TINYINT")
	private Integer id;

	protected CallCapacityLockEntity() {}
}

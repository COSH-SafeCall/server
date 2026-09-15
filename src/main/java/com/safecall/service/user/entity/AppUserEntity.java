package com.safecall.service.user.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.Check;

@Check(name = "ckAppUserEntity", constraints = "octet_length(`kakaoSubjectHash`)=32 AND (`phoneHash` IS NULL OR octet_length(`phoneHash`)=32) AND `status` IN ('ACTIVE','DELETION_PENDING') AND `genderSource` IN ('KAKAO','USER_CONFIRMED','UNKNOWN') AND `birthDateSource` IN ('KAKAO','USER_CONFIRMED','UNKNOWN') AND `version`>0 AND ((`phoneCipher` IS NULL)=(`phoneHash` IS NULL)) AND ((`genderCipher` IS NULL)=(`genderSource`='UNKNOWN')) AND ((`birthDateCipher` IS NULL)=(`birthDateSource`='UNKNOWN'))")
@Entity
@Table(name = "appUser", uniqueConstraints = @UniqueConstraint(name = "uqAppUser1", columnNames = "kakaoSubjectHash"))
public class AppUserEntity {
	@Id
	@Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
	private UUID id;

	@Column(name = "kakaoSubjectHash", nullable = false, columnDefinition = "VARBINARY(32)")
	private byte[] kakaoSubjectHash;

	@Column(name = "kakaoSubjectCipher", nullable = false, columnDefinition = "BLOB")
	private byte[] kakaoSubjectCipher;

	@Column(name = "keyRef", nullable = false, columnDefinition = "TEXT")
	private String keyRef;

	@Column(name = "status", nullable = false, length = 24, columnDefinition = "VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'")
	private String status;

	@Column(name = "nameCipher", columnDefinition = "BLOB")
	private byte[] nameCipher;

	@Column(name = "genderCipher", columnDefinition = "BLOB")
	private byte[] genderCipher;

	@Column(name = "birthDateCipher", columnDefinition = "BLOB")
	private byte[] birthDateCipher;

	@Column(name = "phoneCipher", columnDefinition = "BLOB")
	private byte[] phoneCipher;

	@Column(name = "phoneHash", columnDefinition = "VARBINARY(32)")
	private byte[] phoneHash;

	@Column(name = "genderSource", nullable = false, length = 16, columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'")
	private String genderSource;

	@Column(name = "birthDateSource", nullable = false, length = 16, columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'")
	private String birthDateSource;

	@Column(name = "profileConfirmedAt", columnDefinition = "DATETIME(6)")
	private Instant profileConfirmedAt;

	@Column(name = "createdAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant createdAt;

	@Column(name = "updatedAt", nullable = false, columnDefinition = "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 1")
	private Long version;

	protected AppUserEntity() {}
}

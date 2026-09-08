package com.safecall.service.common.crypto;
import java.util.UUID;
/** 개인별 키를 DB와 분리한다. 운영 KMS/HSM 연결 시 이 경계를 교체한다. */
public interface UserKeyStore {
	String create(UUID userId);
	byte[] read(String keyRef);
	void discard(String keyRef);
}

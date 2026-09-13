package com.safecall.service.common.crypto;
import java.util.UUID;
/** 개인별 키를 DB와 분리한다. 운영 KMS/HSM 연결 시 이 경계를 교체한다. */
public interface UserKeyStore {
	String create(UUID userId);
	byte[] read(String keyRef);
	/** 이미 폐기된 키도 성공으로 처리한다. 삭제 worker의 재시도에서 같은 참조가 반복될 수 있다. */
	void discard(String keyRef);
}

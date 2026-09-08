package com.safecall.service.auth.kakao;
import java.time.LocalDate;
public interface KakaoClient {
	KakaoIdentity verify(String accessToken);
	record KakaoIdentity(String subject, String name, String gender, LocalDate birthDate, String phone) {
		@Override public String toString() { return "KakaoIdentity[redacted]"; }
	}
}

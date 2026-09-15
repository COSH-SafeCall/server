package com.safecall.service.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.api.AuthDtos.ProfilePrefill;
import com.safecall.service.auth.kakao.KakaoClient.KakaoIdentity;
import com.safecall.service.common.crypto.SecretCrypto;

@Component
public class KakaoProfilePrefillCodec {
	private static final int MAX_ENCODED_LENGTH=2048;
	private final SecretCrypto crypto;
	private final JsonMapper mapper;
	private final Clock clock;
	public KakaoProfilePrefillCodec(SecretCrypto crypto,JsonMapper mapper,Clock clock) {
		this.crypto=crypto;this.mapper=mapper;this.clock=clock;
	}
	private record Payload(String name,String gender,LocalDate birthDate,String phone,Instant expiresAt) {}
	public String encode(String sessionCookie,KakaoIdentity identity) {
		if(sessionCookie==null||identity==null)return null;
		var payload=new Payload(identity.name(),identity.gender(),identity.birthDate(),identity.phone(),clock.instant().plusSeconds(600));
		byte[] cipher=crypto.sealResponse(context(sessionCookie),mapper.writeValueAsBytes(payload));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(cipher);
	}
	public ProfilePrefill decode(String sessionCookie,String encoded) {
		if(sessionCookie==null||encoded==null||encoded.isBlank()||encoded.length()>MAX_ENCODED_LENGTH)return null;
		try {
			byte[] cipher=Base64.getUrlDecoder().decode(encoded);
			var payload=mapper.readValue(crypto.openResponse(context(sessionCookie),cipher),Payload.class);
			if(payload.expiresAt()==null||!payload.expiresAt().isAfter(clock.instant()))return null;
			return new ProfilePrefill(payload.name(),payload.gender(),payload.birthDate(),payload.phone());
		} catch(RuntimeException exception) {
			return null;
		}
	}
	private String context(String sessionCookie) {return "KAKAO_PROFILE_PREFILL:v1:"+sessionCookie;}
}

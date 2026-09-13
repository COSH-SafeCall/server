package com.safecall.service.history.service;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.*;

class HistoryCursorTest {
	private final HistoryCursor cursors=new HistoryCursor(new SecretCrypto(Base64.getEncoder().encodeToString(new byte[32]),
		Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
		Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes())));
	@Test void preservesMicrosecondsAndUuidWithUnpaddedAccountBoundSignature() {
		UUID user=UUID.randomUUID();var position=new HistoryCursor.Position(Instant.parse("2026-09-13T01:02:03.123456Z"),UUID.randomUUID());
		String encoded=cursors.encode(user,position);assertThat(encoded).matches("[A-Za-z0-9_-]{16,512}");assertThat(cursors.decode(user,encoded)).isEqualTo(position);
		assertThatThrownBy(()->cursors.decode(UUID.randomUUID(),encoded)).isInstanceOfSatisfying(CustomException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
	}
	@Test void rejectsTamperingPaddingNoncanonicalEncodingAndOversizedInput() {
		UUID user=UUID.randomUUID();String valid=cursors.encode(user,new HistoryCursor.Position(Instant.EPOCH,UUID.randomUUID()));
		byte[] bytes=Base64.getUrlDecoder().decode(valid);bytes[10]^=1;
		for(String invalid:List.of("",valid+"=","x".repeat(513),Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),valid.substring(0,valid.length()-1)))
			assertThatThrownBy(()->cursors.decode(user,invalid)).isInstanceOf(CustomException.class);
	}
}

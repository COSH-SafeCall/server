package com.safecall.service.auth.service;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.CustomException;

class TokenServiceTest {
	private static final String SECRET = "test-signing-secret-with-at-least-32-bytes";
	private final Instant now = Instant.parse("2026-09-08T05:00:00Z");
	private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
	private final SecretCrypto crypto = new SecretCrypto(
		Base64.getEncoder().encodeToString(new byte[32]),
		Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)), SECRET);
	private TokenService service(Clock value) { return new TokenService(SECRET, "safecall", "android", 900000, 1209600000, value, crypto); }

	@Test void roundTripAndGuestExpiryCap() {
		UUID id = UUID.randomUUID();
		var result = service(clock).issue(id, now.plusSeconds(50), now);
		assertThat(service(clock).verify(result.accessToken())).isEqualTo(id);
		assertThat(result.accessExpiresAt()).isEqualTo(now.plusSeconds(50));
		assertThat(result.refreshToken()).hasSize(43);
	}
	@Test void expiredAndTamperedTokensAreRejected() {
		var result = service(clock).issue(UUID.randomUUID(), now.plusSeconds(3600), now);
		assertThatThrownBy(() -> service(Clock.offset(clock, Duration.ofSeconds(901))).verify(result.accessToken())).isInstanceOf(CustomException.class);
		String token = result.accessToken();
		assertThatThrownBy(() -> service(clock).verify("x" + token.substring(1))).isInstanceOf(CustomException.class);
	}
	@Test void issuerAudienceAlgorithmAndRequiredClaimsAreVerified() throws Exception {
		for (String bad : List.of("issuer", "audience", "iat", "exp", "jti", "subject", "algorithm")) {
			var builder = new JWTClaimsSet.Builder().issuer(bad.equals("issuer") ? "other" : "safecall")
				.audience(bad.equals("audience") ? "other" : "android")
				.subject(bad.equals("subject") ? "bad" : UUID.randomUUID().toString());
			if (!bad.equals("iat")) builder.issueTime(Date.from(now));
			if (!bad.equals("exp")) builder.expirationTime(Date.from(now.plusSeconds(100)));
			if (!bad.equals("jti")) builder.jwtID(UUID.randomUUID().toString());
			var token = new SignedJWT(new JWSHeader.Builder(bad.equals("algorithm") ? JWSAlgorithm.HS384 : JWSAlgorithm.HS256)
				.type(JOSEObjectType.JWT).build(), builder.build());
			byte[] key = bad.equals("algorithm") ? new byte[48] : SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			token.sign(new MACSigner(key));
			assertThatThrownBy(() -> service(clock).verify(token.serialize())).as(bad).isInstanceOf(CustomException.class);
		}
	}
	@Test void insecureConfigurationIsRejected() {
		assertThatThrownBy(() -> new TokenService("short", "i", "a", 900000, 1209600000, clock, crypto)).isInstanceOf(IllegalStateException.class);
	}
}

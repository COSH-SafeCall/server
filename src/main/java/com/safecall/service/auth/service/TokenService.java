package com.safecall.service.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;
import com.safecall.service.auth.api.AuthDtos.Tokens;

@Service
public class TokenService {
	private final byte[] key;
	private final String issuer;
	private final String audience;
	private final long accessMs;
	private final long refreshMs;
	private final Clock clock;
	private final SecretCrypto crypto;

	public TokenService(@Value("${app.jwt.secret}") String secret,
		@Value("${app.jwt.issuer}") String issuer, @Value("${app.jwt.audience}") String audience,
		@Value("${app.jwt.access-expiration}") long accessMs,
		@Value("${app.jwt.refresh-expiration}") long refreshMs, Clock clock, SecretCrypto crypto) {
		key = secret.getBytes(StandardCharsets.UTF_8);
		if (key.length < 32 || issuer.isBlank() || audience.isBlank()
			|| accessMs < 1000 || accessMs > 900000 || refreshMs < accessMs || refreshMs > 1209600000) {
			throw new IllegalStateException("Invalid JWT settings.");
		}
		this.issuer = issuer;
		this.audience = audience;
		this.accessMs = accessMs;
		this.refreshMs = refreshMs;
		this.clock = clock;
		this.crypto = crypto;
	}

	public Instant memberExpiry(Instant now) { return now.plusMillis(refreshMs); }

	public Tokens issue(UUID sessionId, Instant refreshExpiry, Instant now) {
		Instant accessExpiry = now.plusMillis(accessMs);
		if (accessExpiry.isAfter(refreshExpiry)) accessExpiry = refreshExpiry;
		// JWT NumericDate와 DB 만료를 초 단위로 일치시킨다.
		accessExpiry = Instant.ofEpochSecond(accessExpiry.getEpochSecond());
		try {
			var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience)
				.subject(sessionId.toString()).issueTime(Date.from(now)).expirationTime(Date.from(accessExpiry))
				.jwtID(UUID.randomUUID().toString()).build();
			var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
			jwt.sign(new MACSigner(key));
			return new Tokens(jwt.serialize(), accessExpiry, crypto.randomToken(), refreshExpiry, sessionId);
		} catch (JOSEException exception) { throw new IllegalStateException("Token issuance failed."); }
	}

	public UUID verify(String token) {
		try {
			if (token == null || token.length() > 4096) throw new IllegalArgumentException();
			var jwt = SignedJWT.parse(token);
			if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
				|| !JOSEObjectType.JWT.equals(jwt.getHeader().getType())
				|| !jwt.verify(new MACVerifier(key))) throw new IllegalArgumentException();
			var claims = jwt.getJWTClaimsSet();
			Instant now = clock.instant();
			if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(audience)
				|| claims.getIssueTime() == null || claims.getExpirationTime() == null
				|| claims.getIssueTime().toInstant().isAfter(now)
				|| !claims.getExpirationTime().toInstant().isAfter(now)
				|| !claims.getExpirationTime().after(claims.getIssueTime())) throw new IllegalArgumentException();
			UUID.fromString(claims.getJWTID());
			return UUID.fromString(claims.getSubject());
		} catch (Exception exception) { throw new CustomException(ErrorCode.SESSION_EXPIRED); }
	}
}

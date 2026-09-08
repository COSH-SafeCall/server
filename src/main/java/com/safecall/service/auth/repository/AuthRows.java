package com.safecall.service.auth.repository;
import java.time.Instant;
import java.util.UUID;
import com.safecall.service.auth.api.AuthDtos.Step;
public final class AuthRows {
	private AuthRows() {}
	public record Session(UUID id, UUID installationId, UUID userId, String kind, Step step,
		long version, String status, Instant createdAt, Instant expiresAt) {}
	public record Credential(UUID id, UUID sessionId, int generation, byte[] accessHash, byte[] refreshHash,
		Instant accessExpiresAt, Instant refreshExpiresAt, Instant consumedAt) {}
	public record User(UUID id, String status, String keyRef, byte[] nameCipher, byte[] genderCipher,
		byte[] birthDateCipher, byte[] phoneCipher, String genderSource, String birthDateSource,
		Instant confirmedAt, long version) {}
	public record Replay(UUID id, UUID ownerSessionId, UUID resourceId, byte[] requestHash,
		String status, byte[] responseCipher, Instant responseExpiresAt) {}
}

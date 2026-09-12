package com.safecall.service.common.crypto;
import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
class SecretCryptoTest {
	private final String first = Base64.getEncoder().encodeToString(new byte[32]);
	private final String second = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
	@Test void authenticatedEncryptionBindsUserAndField() {
		var crypto = new SecretCrypto(first, second, Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		byte[] key = crypto.randomBytes(32);
		byte[] plain = "synthetic-private-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] cipher = crypto.seal(key, "user:name", plain);
		assertThat(crypto.open(key, "user:name", cipher)).isEqualTo(plain);
		assertThatThrownBy(() -> crypto.open(key, "other:name", cipher)).isInstanceOf(IllegalStateException.class);
		cipher[cipher.length - 1] ^= 1;
		assertThatThrownBy(() -> crypto.open(key, "user:name", cipher)).isInstanceOf(IllegalStateException.class);
	}
	@Test void hashDomainsAndKeysAreIndependent() {
		var crypto = new SecretCrypto(first, second, Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertThat(crypto.hash("ACCESS", "same")).isNotEqualTo(crypto.hash("REFRESH", "same"));
		assertThatThrownBy(() -> new SecretCrypto(first, first, "anything")).isInstanceOf(IllegalStateException.class);
	}
	@Test void idempotencyMatchesLengthPrefixedContractVector() {
		var crypto=new SecretCrypto(first,second,Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		byte[] result=crypto.idempotency("USER",java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"),"CONTACT","22222222-2222-4222-8222-222222222222");
		assertThat(java.util.HexFormat.of().formatHex(result)).isEqualTo("d4b0123d1aa9b828c21eba3d002b235ae37ea3847fefaa10f988eaf01afbdc4c");
	}
}

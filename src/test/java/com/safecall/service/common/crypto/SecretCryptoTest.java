package com.safecall.service.common.crypto;
import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
class SecretCryptoTest {
	private final String first = Base64.getEncoder().encodeToString(new byte[32]);
	private final String second = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
	@Test void authenticatedEncryptionBindsUserAndField() {
		var crypto = new SecretCrypto(first, second, "independent-jwt-signing-key-that-is-long");
		byte[] key = crypto.randomBytes(32);
		byte[] plain = "synthetic-private-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] cipher = crypto.seal(key, "user:name", plain);
		assertThat(crypto.open(key, "user:name", cipher)).isEqualTo(plain);
		assertThatThrownBy(() -> crypto.open(key, "other:name", cipher)).isInstanceOf(IllegalStateException.class);
		cipher[cipher.length - 1] ^= 1;
		assertThatThrownBy(() -> crypto.open(key, "user:name", cipher)).isInstanceOf(IllegalStateException.class);
	}
	@Test void hashDomainsAndKeysAreIndependent() {
		var crypto = new SecretCrypto(first, second, "independent-jwt-signing-key-that-is-long");
		assertThat(crypto.hash("ACCESS", "same")).isNotEqualTo(crypto.hash("REFRESH", "same"));
		assertThatThrownBy(() -> new SecretCrypto(first, first, "anything")).isInstanceOf(IllegalStateException.class);
	}
}

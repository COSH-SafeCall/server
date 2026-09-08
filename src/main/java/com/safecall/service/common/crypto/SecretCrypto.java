package com.safecall.service.common.crypto;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCrypto {
	private final byte[] hmacKey;
	private final byte[] responseKey;
	private final SecureRandom random = new SecureRandom();
	public SecretCrypto(@Value("${app.crypto.hmac-secret}") String hmacSecret,
		@Value("${app.crypto.response-secret}") String responseSecret,
		@Value("${app.jwt.secret}") String jwtSecret) {
		hmacKey = decodeKey(hmacSecret);
		responseKey = decodeKey(responseSecret);
		if (MessageDigest.isEqual(hmacKey, responseKey)
			|| hmacSecret.equals(jwtSecret) || responseSecret.equals(jwtSecret)
			|| MessageDigest.isEqual(hmacKey, jwtSecret.getBytes(StandardCharsets.UTF_8))
			|| MessageDigest.isEqual(responseKey, jwtSecret.getBytes(StandardCharsets.UTF_8))) {
			throw new IllegalStateException("Authentication keys must be independent.");
		}
	}
	private byte[] decodeKey(String value) {
		try {
			byte[] key = Base64.getDecoder().decode(value);
			if (key.length != 32) throw new IllegalArgumentException();
			return key;
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Crypto secrets must contain 32 random bytes encoded as Base64.");
		}
	}
	public byte[] hash(String domain, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
			return mac.doFinal((domain + "\0" + value).getBytes(StandardCharsets.UTF_8));
		} catch (Exception exception) { throw new IllegalStateException("HMAC operation failed."); }
	}
	public String randomToken() { return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32)); }
	public byte[] randomBytes(int size) { byte[] bytes = new byte[size]; random.nextBytes(bytes); return bytes; }
	public boolean isEqual(byte[] left, byte[] right) { return left != null && right != null && MessageDigest.isEqual(left, right); }
	public byte[] sealResponse(String context, byte[] bytes) { return seal(responseKey, context, bytes); }
	public byte[] openResponse(String context, byte[] bytes) { return open(responseKey, context, bytes); }
	public byte[] seal(byte[] key, String context, byte[] bytes) {
		if (bytes == null) return null;
		try {
			byte[] nonce = randomBytes(12);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(bytes);
			return ByteBuffer.allocate(1 + nonce.length + encrypted.length).put((byte) 1).put(nonce).put(encrypted).array();
		} catch (Exception exception) { throw new IllegalStateException("Encryption failed."); }
	}
	public byte[] open(byte[] key, String context, byte[] bytes) {
		if (bytes == null) return null;
		try {
			if (bytes.length < 29 || bytes[0] != 1) throw new IllegalArgumentException();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
			cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
			return cipher.doFinal(Arrays.copyOfRange(bytes, 13, bytes.length));
		} catch (Exception exception) { throw new IllegalStateException("Decryption failed."); }
	}
}

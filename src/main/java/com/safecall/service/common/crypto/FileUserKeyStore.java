package com.safecall.service.common.crypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("local & !prod")
public class FileUserKeyStore implements UserKeyStore {
	private final Path directory;
	private final SecretCrypto crypto;
	public FileUserKeyStore(@Value("${app.crypto.key-directory}") String directory, SecretCrypto crypto) {
		this.directory = Path.of(directory).toAbsolutePath().normalize();
		this.crypto = crypto;
	}
	@Override
	public String create(UUID userId) {
		try {
			Files.createDirectories(directory);
			String keyRef = userId.toString();
			Files.write(directory.resolve(keyRef), crypto.randomBytes(32), StandardOpenOption.CREATE_NEW);
			return keyRef;
		} catch (Exception exception) { throw new IllegalStateException("User key provisioning failed."); }
	}
	@Override
	public byte[] read(String keyRef) {
		try {
			if (!UUID.fromString(keyRef).toString().equals(keyRef)) throw new IllegalArgumentException();
			byte[] key = Files.readAllBytes(directory.resolve(keyRef));
			if (key.length != 32) throw new IllegalArgumentException();
			return key;
		} catch (Exception exception) { throw new IllegalStateException("User key unavailable."); }
	}
	@Override
	public void discard(String keyRef) {
		try {
			if (!UUID.fromString(keyRef).toString().equals(keyRef)) throw new IllegalArgumentException();
			Files.deleteIfExists(directory.resolve(keyRef));
		} catch (Exception exception) { throw new IllegalStateException("User key removal failed."); }
	}
}

package com.safecall.service.history.service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.*;

@Component
public class HistoryCursor {
	private final SecretCrypto crypto;
	public HistoryCursor(SecretCrypto crypto) { this.crypto=crypto; }
	public record Position(Instant createdAt, UUID id) {}
	public String encode(UUID user, Position position) {
		byte[] payload=ByteBuffer.allocate(29).put((byte)1).putLong(position.createdAt().getEpochSecond())
			.putInt(position.createdAt().getNano()).putLong(position.id().getMostSignificantBits()).putLong(position.id().getLeastSignificantBits()).array();
		byte[] signature=crypto.hashBytes("USAGE_CURSOR:"+user,payload);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(61).put(payload).put(signature).array());
	}
	public Position decode(UUID user, String cursor) {
		try {
			if (!cursor.matches("[A-Za-z0-9_-]{16,512}")) throw new IllegalArgumentException();
			byte[] bytes=Base64.getUrlDecoder().decode(cursor);
			if (bytes.length!=61 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)
				|| !crypto.isEqual(Arrays.copyOfRange(bytes,29,61),crypto.hashBytes("USAGE_CURSOR:"+user,Arrays.copyOf(bytes,29))))
				throw new IllegalArgumentException();
			var buffer=ByteBuffer.wrap(bytes);
			if (buffer.get()!=1) throw new IllegalArgumentException();
			long seconds=buffer.getLong(); int nanos=buffer.getInt();
			if (nanos<0 || nanos>=1_000_000_000 || nanos%1000!=0) throw new IllegalArgumentException();
			return new Position(Instant.ofEpochSecond(seconds,nanos),new UUID(buffer.getLong(),buffer.getLong()));
		} catch (RuntimeException exception) { throw new CustomException(ErrorCode.INVALID_CURSOR); }
	}
}

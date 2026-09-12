package com.safecall.service.user.service;
import java.text.Normalizer;
import java.time.LocalDate;
import com.safecall.service.common.error.*;

public final class UserValues {
	private UserValues() {}
	public static String text(String value, int maximum) {
		if (value == null || value.codePoints().anyMatch(c -> Character.isISOControl(c)
			|| Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE
			|| Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) {
			throw new CustomException(ErrorCode.VALIDATION_FAILED);
		}
		String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
		if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > maximum) {
			throw new CustomException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}
	public static String phone(String value) {
		if (value == null || !value.matches("[+0-9 -]+")) throw new CustomException(ErrorCode.INVALID_PHONE);
		String normalized = value.replace(" ", "").replace("-", "");
		if(normalized.startsWith("+82"))normalized="0"+normalized.substring(3);
		if (!normalized.matches("010[0-9]{8}")) throw new CustomException(ErrorCode.INVALID_PHONE);
		return normalized;
	}
	public static LocalDate birthDate(String value, LocalDate today) {
		if (value == null) return null;
		try {
			if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
			LocalDate date = LocalDate.parse(value);
			if (date.getYear() < 1 || date.isAfter(today)) throw new IllegalArgumentException();
			return date;
		} catch (RuntimeException exception) { throw new CustomException(ErrorCode.INVALID_BIRTH_DATE); }
	}
}

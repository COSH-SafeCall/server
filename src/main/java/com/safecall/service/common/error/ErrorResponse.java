package com.safecall.service.common.error;
import java.util.List;
public record ErrorResponse(String timestamp, int status, String code, String message,
	List<FieldError> errors, String path) {
	public record FieldError(String field, Object value, String reason) {}
}

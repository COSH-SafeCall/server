package com.safecall.service.common.error;
public class CustomException extends RuntimeException {
	private final ErrorCode errorCode;
	private final Integer retryAfter;
	public CustomException(ErrorCode errorCode) { this(errorCode, null); }
	public CustomException(ErrorCode errorCode, Integer retryAfter) {
		super(errorCode.name());
		this.errorCode = errorCode;
		this.retryAfter = retryAfter;
	}
	public ErrorCode errorCode() { return errorCode; }
	public Integer retryAfter() { return retryAfter; }
}

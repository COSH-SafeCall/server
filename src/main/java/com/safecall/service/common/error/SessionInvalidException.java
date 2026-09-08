package com.safecall.service.common.error;
/** 만료/재사용 탐지에 따른 폐기는 오류 응답을 반환하더라도 커밋한다. */
public class SessionInvalidException extends CustomException {
	public SessionInvalidException(ErrorCode errorCode) { super(errorCode); }
}

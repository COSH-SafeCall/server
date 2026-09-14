package com.safecall.service.user.service;

import java.util.List;
import java.util.Set;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;

/** Fixed consent contract rendered as static content by the web client. */
public final class ConsentPolicy {
	public static final int VERSION=1;
	public static final List<String> CODES=List.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING");
	public static final Set<String> REQUIRED=Set.of("PRIVACY_PROCESSING","AI_CALL");
	private ConsentPolicy() {}
	public static void requireCode(String code) {
		if(!CODES.contains(code))throw new CustomException(ErrorCode.INVALID_CONSENT);
	}
	public static void requireVersion(int version) {
		if(version!=VERSION)throw new CustomException(ErrorCode.INVALID_CONSENT);
	}
	public static void require(String code,int version) {
		requireCode(code);requireVersion(version);
	}
}

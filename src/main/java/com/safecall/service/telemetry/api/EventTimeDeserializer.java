package com.safecall.service.telemetry.api;

import java.time.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import com.safecall.service.common.error.*;

/** O01 시각은 숫자 epoch나 로컬 시각으로 대체할 수 없는 RFC3339 문자열이다. */
public class EventTimeDeserializer extends ValueDeserializer<Instant> {
	@Override public Instant deserialize(JsonParser parser,DeserializationContext context) {
		if(!parser.hasToken(JsonToken.VALUE_STRING)) throw new CustomException(ErrorCode.INVALID_REQUEST);
		String value=parser.getString();
		if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})"))
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		try { return OffsetDateTime.parse(value).toInstant(); }
		catch(DateTimeException ex) { throw new CustomException(ErrorCode.INVALID_REQUEST); }
	}
}

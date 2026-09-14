package com.safecall.service.common.api;

import java.time.*;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import com.safecall.service.common.error.*;

/** RFC3339 string input, normalized to the UTC DATETIME(6) storage contract. */
public class DatabaseInstantDeserializer extends ValueDeserializer<Instant> {
	@Override public Instant deserialize(JsonParser parser,DeserializationContext context) {
		if(!parser.hasToken(JsonToken.VALUE_STRING))throw new CustomException(ErrorCode.INVALID_REQUEST);
		String value=parser.getString();
		if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})"))throw new CustomException(ErrorCode.INVALID_REQUEST);
		try {
			Instant instant=OffsetDateTime.parse(value).toInstant();
			if(instant.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || !instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z")))throw new CustomException(ErrorCode.INVALID_REQUEST);
			return instant.truncatedTo(ChronoUnit.MICROS);
		}catch(DateTimeException ex){throw new CustomException(ErrorCode.INVALID_REQUEST);}
	}
}

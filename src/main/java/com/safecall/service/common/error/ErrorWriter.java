package com.safecall.service.common.error;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ErrorWriter {
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
	private final Clock clock;
	private final JsonMapper mapper;
	public ErrorWriter(Clock clock, JsonMapper mapper) { this.clock = clock; this.mapper = mapper; }
	public ErrorResponse body(ErrorCode code, List<ErrorResponse.FieldError> errors, String path) {
		return new ErrorResponse(LocalDateTime.ofInstant(clock.instant(),java.time.ZoneOffset.UTC).format(FORMAT)+"Z", code.status(),
			code.code(), code.message(), errors, path);
	}
	public void write(HttpServletRequest request, HttpServletResponse response, CustomException exception) throws IOException {
		response.setStatus(exception.errorCode().status());
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		if (exception.retryAfter() != null) response.setHeader("Retry-After", exception.retryAfter().toString());
		response.getWriter().write(mapper.writeValueAsString(body(exception.errorCode(), List.of(), request.getRequestURI())));
	}
}

package com.safecall.service.common.error;
import java.util.List;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {
	private final ErrorWriter writer;
	public ApiErrorController(ErrorWriter writer) { this.writer = writer; }
	@RequestMapping("/error")
	public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
		Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		int value = status instanceof Integer number ? number : 404;
		ErrorCode code = switch (value) {
			case 400 -> ErrorCode.INVALID_REQUEST;
			case 401 -> ErrorCode.AUTHENTICATION_REQUIRED;
			case 403 -> ErrorCode.ACCESS_DENIED;
			case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 406 -> ErrorCode.NOT_ACCEPTABLE;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			default -> ErrorCode.INTERNAL_SERVER_ERROR;
		};
		Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		String path = original instanceof String uri ? uri.split("\\?", 2)[0] : request.getRequestURI();
		return ResponseEntity.status(code.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Cache-Control", "no-store")
			.body(writer.body(code, List.of(), path));
	}
}

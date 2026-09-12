package com.safecall.service.common.error;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private final ErrorWriter writer;
	public GlobalExceptionHandler(ErrorWriter writer) { this.writer = writer; }
	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ErrorResponse> business(CustomException exception, HttpServletRequest request) {
		var builder = ResponseEntity.status(exception.errorCode().status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
		if (exception.retryAfter() != null) builder.header("Retry-After", exception.retryAfter().toString());
		return builder.body(writer.body(exception.errorCode(), List.of(), request.getRequestURI()));
	}
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		// 거절된 값과 validator 메시지에 포함될 수 있는 입력값을 반사하지 않는다.
		var errors = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> new ErrorResponse.FieldError(error.getField(), null, "입력값을 확인해 주세요.")).toList();
		return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.body(writer.body(ErrorCode.VALIDATION_FAILED, errors, request.getRequestURI()));
	}
	@ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
		MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
	public ResponseEntity<ErrorResponse> malformed(Exception exception, HttpServletRequest request) {
		for (Throwable cause=exception; cause!=null; cause=cause.getCause()) {
			if(cause instanceof CustomException business)return business(business,request);
			if(cause instanceof tools.jackson.databind.exc.InvalidFormatException format
				&& format.getTargetType()==com.safecall.service.user.api.UserDtos.AlertMode.class)
				return response(ErrorCode.INVALID_ALERT_MODE,request);
		}
		return response(ErrorCode.INVALID_REQUEST, request);
	}
	@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
	public ResponseEntity<ErrorResponse> missing(Exception exception, HttpServletRequest request) {
		return response(ErrorCode.RESOURCE_NOT_FOUND, request);
	}
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> method(Exception exception, HttpServletRequest request) {
		return response(ErrorCode.METHOD_NOT_ALLOWED, request);
	}
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> media(Exception exception, HttpServletRequest request) {
		return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request);
	}
	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	public ResponseEntity<ErrorResponse> accept(Exception exception, HttpServletRequest request) {
		return response(ErrorCode.NOT_ACCEPTABLE, request);
	}
	@ExceptionHandler(PessimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> contention(Exception exception, HttpServletRequest request) {
		return business(new CustomException(ErrorCode.REQUEST_IN_PROGRESS, 1), request);
	}
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
		// SQL 문장, 공급자 응답, 토큰 및 예외 메시지는 기록하거나 응답하지 않는다.
		org.slf4j.LoggerFactory.getLogger(getClass()).error("API failure type={}", exception.getClass().getSimpleName());
		return response(ErrorCode.INTERNAL_SERVER_ERROR, request);
	}
	private ResponseEntity<ErrorResponse> response(ErrorCode code, HttpServletRequest request) {
		return ResponseEntity.status(code.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.body(writer.body(code, List.of(), request.getRequestURI()));
	}
}

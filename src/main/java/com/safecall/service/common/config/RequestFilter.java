package com.safecall.service.common.config;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.safecall.service.auth.service.AuthMaintenance;
import com.safecall.service.common.error.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
	private static final Set<String> AUTH_PATHS = Set.of("/api/v1/auth/guest", "/api/v1/auth/kakao", "/api/v1/auth/refresh");
	private final ErrorWriter writer;
	private final AuthMaintenance maintenance;
	public RequestFilter(ErrorWriter writer, AuthMaintenance maintenance) { this.writer = writer; this.maintenance = maintenance; }
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws IOException, ServletException {
		response.setHeader("X-Request-Id", UUID.randomUUID().toString());
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("X-Content-Type-Options", "nosniff");
		try {
			validateIdempotencyKey(request);
			if ("POST".equals(request.getMethod()) && AUTH_PATHS.contains(request.getRequestURI())) {
				// 신뢰할 프록시 구성이 없는 상태에서 X-Forwarded-For를 인증 제한 키로 사용하지 않는다.
				maintenance.checkRate(request.getRemoteAddr());
			}
			chain.doFilter(request, response);
		} catch (CustomException exception) {
			if (!response.isCommitted()) writer.write(request, response, exception);
		} catch (Exception exception) {
			if (!response.isCommitted()) writer.write(request, response, new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
		}
	}
	private void validateIdempotencyKey(HttpServletRequest request) {
		String key = request.getHeader("Idempotency-Key");
		if (key == null) return;
		try {
			// UUID.fromString은 축약 표현도 허용하므로 원래 문자열 형식까지 확인한다.
			if (!UUID.fromString(key).toString().equalsIgnoreCase(key)
				|| java.util.Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException exception) { throw new CustomException(ErrorCode.INVALID_REQUEST); }
	}
}

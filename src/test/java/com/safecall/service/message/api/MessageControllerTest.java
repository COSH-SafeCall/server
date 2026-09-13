package com.safecall.service.message.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import com.safecall.service.common.error.CustomException;
import com.safecall.service.common.error.ErrorCode;
import com.safecall.service.message.service.MessageService;

class MessageControllerTest {
	@Test void declaredBodyIsRejectedWithoutWaitingForPayload() throws Exception {
		var request=mock(HttpServletRequest.class);
		when(request.getContentLengthLong()).thenReturn(16L);
		assertRejectedWithoutReading(request);
	}
	@Test void chunkedBodyIsRejectedWithoutWaitingForFirstChunk() throws Exception {
		var request=mock(HttpServletRequest.class);
		when(request.getContentLengthLong()).thenReturn(-1L);
		when(request.getHeader("Transfer-Encoding")).thenReturn("chunked");
		assertRejectedWithoutReading(request);
	}
	private void assertRejectedWithoutReading(HttpServletRequest request) throws Exception {
		when(request.getParameterMap()).thenReturn(Map.of());
		when(request.getInputStream()).thenThrow(new AssertionError("A stalled body must not be read."));
		var service=mock(MessageService.class);
		assertThatThrownBy(() -> new MessageController(service).compose(request))
			.isInstanceOf(CustomException.class)
			.satisfies(error -> assertThat(((CustomException)error).errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
		verify(request,never()).getInputStream();
		verifyNoInteractions(service);
	}
}

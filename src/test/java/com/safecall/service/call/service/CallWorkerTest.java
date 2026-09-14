package com.safecall.service.call.service;

import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import com.safecall.service.call.gemini.GeminiClient;
import com.safecall.service.call.repository.CallRepository;

class CallWorkerTest {
	@Test void failedReaperDoesNotBlockOtherCallsOrNewIssuanceOnRepeatedTicks() {
		var service=mock(CallService.class);var repository=mock(CallRepository.class);
		UUID failed=UUID.randomUUID(),healthy=UUID.randomUUID(),pending=UUID.randomUUID();
		when(repository.expired(any(Instant.class))).thenReturn(List.of(failed,healthy));
		when(repository.pending()).thenReturn(List.of(pending));
		doThrow(new CannotAcquireLockException("synthetic lock timeout")).when(service).reap(failed);
		var worker=new CallWorker(service,repository,mock(GeminiClient.class),Clock.systemUTC());
		try {
			worker.tick();
			verify(service,timeout(2000)).claim(pending);
			worker.tick();
			verify(service,timeout(2000).times(2)).claim(pending);
			verify(service,times(2)).reap(failed);
			verify(service,times(2)).reap(healthy);
		} finally {worker.close();}
	}

	@Test void failedExpiryLookupStillDispatchesPendingCalls() {
		var service=mock(CallService.class);var repository=mock(CallRepository.class);
		UUID pending=UUID.randomUUID();
		when(repository.expired(any(Instant.class))).thenThrow(new CannotAcquireLockException("synthetic lookup failure"));
		when(repository.pending()).thenReturn(List.of(pending));
		var worker=new CallWorker(service,repository,mock(GeminiClient.class),Clock.systemUTC());
		try {
			worker.tick();
			verify(service,timeout(2000)).claim(pending);
		} finally {worker.close();}
	}
}

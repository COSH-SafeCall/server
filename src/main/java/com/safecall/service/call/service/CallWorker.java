package com.safecall.service.call.service;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.safecall.service.call.gemini.GeminiClient;
import com.safecall.service.call.repository.CallRepository;

@Component
public class CallWorker {
	private final CallService service;
	private final CallRepository repository;
	private final GeminiClient gemini;
	private final Clock clock;
	private final ExecutorService executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),
		Thread.ofPlatform().daemon().name("call-issuer-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
	public CallWorker(CallService service,CallRepository repository,GeminiClient gemini,Clock clock) {
		this.service=service; this.repository=repository; this.gemini=gemini; this.clock=clock;
	}
	@Scheduled(fixedDelayString="${app.call.worker-delay-ms}",initialDelayString="${app.call.worker-delay-ms}")
	public void tick() {
		try {
			for(UUID id:repository.expired(clock.instant()))service.reap(id);
			for(UUID id:repository.pending()) {
				try { executor.execute(()->runOne(id)); }
				catch(RejectedExecutionException busy) { break; }
			}
		} catch(RuntimeException exception) { failure(); }
	}
	public void runOne(UUID id) {
		try {
			var request=service.claim(id);
			if(request==null)return;
			String token;
			try { token=gemini.issue(request); }
			catch(GeminiClient.IssueException exception) { service.finish(id,request,null,exception.isUnknown()); return; }
			catch(RuntimeException exception) { service.finish(id,request,null,true); return; }
			service.finish(id,request,token,null);
		} catch(RuntimeException exception) {
			// Never re-issue an ISSUING grant after a DB failure or uncertain external result.
			// The reaper changes abandoned issuance to UNKNOWN within its creation deadline.
			failure();
		}
	}
	private void failure() { org.slf4j.LoggerFactory.getLogger(getClass()).error("Call worker failed; pending cleanup will retry."); }
	@PreDestroy public void close() { executor.shutdownNow(); }
}

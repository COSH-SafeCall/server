package com.safecall.service.auth.kakao;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** Completes only after EOF; rejects oversized token responses before buffering them. */
final class BoundedTokenBodySubscriber implements BodySubscriber<byte[]> {
	private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
	private final CompletableFuture<byte[]> body=new CompletableFuture<>();
	private Subscription subscription;
	@Override public CompletionStage<byte[]> getBody(){return body;}
	@Override public void onSubscribe(Subscription value){subscription=value;value.request(Long.MAX_VALUE);}
	@Override public void onNext(List<ByteBuffer> buffers){
		if(body.isDone())return;
		for(ByteBuffer buffer:buffers){
			if(buffer.remaining()>65536-bytes.size()){
				subscription.cancel();body.completeExceptionally(new IllegalStateException("Token response exceeds size limit."));return;
			}
			byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
		}
	}
	@Override public void onError(Throwable error){body.completeExceptionally(error);}
	@Override public void onComplete(){body.complete(bytes.toByteArray());}
}

package com.safecall.service.auth.kakao;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.service.WebPolicy;
import com.safecall.service.common.error.*;

class HttpKakaoCodeClientTest {
    @Test @SuppressWarnings("unchecked")
    void stalledBodyFailsWithinDeadlineWithoutVerifyingIdentity() throws Exception {
        var headers=new CountDownLatch(1);var release=new CountDownLatch(1);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
            try(var stream=exchange.getResponseBody()){
                stream.write("{\"access_token\":\"".getBytes());stream.flush();headers.countDown();
                try{release.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                stream.write("synthetic\",\"token_type\":\"bearer\"}".getBytes());
            }
        });server.start();
        try(var real=HttpClient.newHttpClient();var executor=Executors.newSingleThreadExecutor()){
            var policy=mock(WebPolicy.class);when(policy.redirectUri()).thenReturn("https://safecall.test/callback");
            when(policy.clientId()).thenReturn("synthetic");when(policy.clientSecret()).thenReturn("");
            var identity=mock(KakaoClient.class);
            var http=mock(HttpClient.class);
            when(http.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
                HttpRequest request=inv.getArgument(0);
                assertThat(request.timeout()).contains(Duration.ofSeconds(5));
                var local=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                    .timeout(request.timeout().orElseThrow()).POST(request.bodyPublisher().orElseThrow()).build();
                return real.sendAsync(local,inv.<HttpResponse.BodyHandler<byte[]>>getArgument(1));
            });
            var client=new HttpKakaoCodeClient(policy,identity,JsonMapper.builder().build());
            ReflectionTestUtils.setField(client,"http",http);
            var future=executor.submit(()->client.exchange("synthetic-code",policy.redirectUri()));
            try{
                assertThat(headers.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->future.get(7,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CustomException.class).hasRootCauseMessage("KAKAO_UNAVAILABLE");
                verifyNoInteractions(identity);
            }finally{release.countDown();}
        }finally{release.countDown();server.stop(0);}
    }

    @Test void responseSubscriberWaitsForEofAndAcceptsExactly64KiB() {
        var subscriber=new BoundedTokenBodySubscriber();
        var subscription=mock(Flow.Subscription.class);subscriber.onSubscribe(subscription);
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[32768])));
        assertThat(subscriber.getBody().toCompletableFuture()).isNotDone();
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[32768])));
        subscriber.onComplete();
        assertThat(subscriber.getBody().toCompletableFuture().join()).hasSize(65536);
        verify(subscription,never()).cancel();
    }
    @Test @SuppressWarnings("unchecked") void completeBoundedResponseVerifiesIdentityButOversizeAndHttpErrorsDoNot() throws Exception {
        String token="{\"access_token\":\"synthetic\",\"token_type\":\"bearer\"}";
        var body=new java.util.concurrent.atomic.AtomicReference<>(token+" ".repeat(65536-token.length()));
        var status=new java.util.concurrent.atomic.AtomicInteger(200);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),bytes.length);try(var stream=exchange.getResponseBody()){stream.write(bytes);}});server.start();
        try(var real=HttpClient.newHttpClient()){
            var policy=mock(WebPolicy.class);when(policy.redirectUri()).thenReturn("https://safecall.test/callback");when(policy.clientId()).thenReturn("synthetic");when(policy.clientSecret()).thenReturn("");
            var identity=mock(KakaoClient.class);var http=mock(HttpClient.class);
            when(http.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{HttpRequest request=inv.getArgument(0);return real.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).timeout(request.timeout().orElseThrow()).POST(request.bodyPublisher().orElseThrow()).build(),inv.<HttpResponse.BodyHandler<byte[]>>getArgument(1));});
            var client=new HttpKakaoCodeClient(policy,identity,JsonMapper.builder().build());ReflectionTestUtils.setField(client,"http",http);
            client.exchange("synthetic",policy.redirectUri());verify(identity).verify("synthetic");clearInvocations(identity);
            body.set(body.get()+" ");
            assertThatThrownBy(()->client.exchange("synthetic",policy.redirectUri())).isInstanceOf(CustomException.class).hasMessage("KAKAO_UNAVAILABLE");
            status.set(503);body.set(token);
            assertThatThrownBy(()->client.exchange("synthetic",policy.redirectUri())).isInstanceOf(CustomException.class).hasMessage("KAKAO_UNAVAILABLE");verifyNoInteractions(identity);
        }finally{server.stop(0);}
    }
    @Test void responseSubscriberCancelsAtFirstByteOverLimit() {
        var subscriber=new BoundedTokenBodySubscriber();
        var subscription=mock(Flow.Subscription.class);subscriber.onSubscribe(subscription);
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[65536]),java.nio.ByteBuffer.wrap(new byte[1])));
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
        verify(subscription).cancel();
        subscriber.onComplete();assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
    @Test @SuppressWarnings("unchecked") void interruptionCancelsPendingResponseAndPreservesInterruptFlag() {
        var policy=mock(WebPolicy.class);when(policy.redirectUri()).thenReturn("https://safecall.test/callback");
        when(policy.clientId()).thenReturn("synthetic");when(policy.clientSecret()).thenReturn("");
        var identity=mock(KakaoClient.class);var http=mock(HttpClient.class);
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        when(http.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        var client=new HttpKakaoCodeClient(policy,identity,JsonMapper.builder().build());ReflectionTestUtils.setField(client,"http",http);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->client.exchange("synthetic",policy.redirectUri())).isInstanceOfSatisfying(CustomException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.KAKAO_UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(pending).isCancelled();verifyNoInteractions(identity);
        } finally {Thread.interrupted();}
    }
}

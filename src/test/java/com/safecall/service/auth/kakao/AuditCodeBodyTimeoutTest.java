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

class AuditCodeBodyTimeoutTest {
    @Test @SuppressWarnings("unchecked")
    void bodyReadOutlivesConfiguredFiveSecondTimeout() throws Exception {
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
            when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
                HttpRequest request=inv.getArgument(0);
                assertThat(request.timeout()).contains(Duration.ofSeconds(5));
                var local=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                    .timeout(request.timeout().orElseThrow()).POST(request.bodyPublisher().orElseThrow()).build();
                return real.send(local,inv.<HttpResponse.BodyHandler<java.io.InputStream>>getArgument(1));
            });
            var client=new HttpKakaoCodeClient(policy,identity,JsonMapper.builder().build());
            ReflectionTestUtils.setField(client,"http",http);
            var future=executor.submit(()->client.exchange("synthetic-code",policy.redirectUri()));
            try{
                assertThat(headers.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->future.get(6,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            }finally{release.countDown();}
            future.get(5,TimeUnit.SECONDS);verify(identity).verify("synthetic");
        }finally{release.countDown();server.stop(0);}
    }
}

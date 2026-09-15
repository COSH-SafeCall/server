package com.safecall.service.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.safecall.service.auth.repository.AuthRepository.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.kakao.*;
import com.safecall.service.auth.repository.*;
import com.safecall.service.auth.service.*;
import com.safecall.service.call.gemini.*;
import com.safecall.service.call.service.*;
import com.safecall.service.common.crypto.*;
import com.safecall.service.common.error.*;
import com.safecall.service.user.service.*;
@Tag("mysql") @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebIntegrationTest {
	static final Instant START=Instant.parse("2026-09-12T00:00:00Z");static final String ORIGIN="https://safecall.test";
	@LocalServerPort int port;
	@Autowired JdbcTemplate jdbc;@Autowired JsonMapper mapper;@Autowired MutableClock clock;@Autowired SecretCrypto crypto;
	@Autowired CallWorker worker;@Autowired CallService calls;@Autowired ConsentCleanup cleanup;@Autowired AuthMaintenance maintenance;
	@Autowired com.safecall.service.call.repository.CallRepository callRepository;
	@Autowired AccountLocalCleanup accountsCleanup;@Autowired WebRetention retention;@MockitoSpyBean UserKeyStore userKeys;
	@MockitoBean KakaoCodeClient kakao;@MockitoBean GeminiClient gemini;
	@MockitoSpyBean AuthRepository authRepository;
	@Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
	@Autowired KeyCreationJournal creationJournal;
	@MockitoSpyBean KeyDiscardQueue discardQueue;@Autowired TransientKeys transientKeys;
	@MockitoSpyBean com.safecall.service.message.service.MessagePolicy messagePolicy;
	@Autowired com.safecall.service.history.service.UsageHistoryCleanup historyCleanup;
	@Autowired com.safecall.service.history.service.AccountExternalCleanup externalCleanup;
	@MockitoBean KakaoUnlinkClient unlink;
	@Autowired org.springframework.context.ApplicationContext applicationContext;
	@MockitoSpyBean com.safecall.service.telemetry.service.TelemetryPolicy telemetryPolicy;
	private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
	@DynamicPropertySource static void config(DynamicPropertyRegistry r){
		String url=System.getenv("AUTH_TEST_DB_URL");
		if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?!3306/)[0-9]+/safecall_auth_test_[0-9a-f]{16}\\?.*"))throw new IllegalStateException("Use scripts/verify_auth.py and its disposable DB.");
		r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"");
		r.add("app.crypto.hmac-secret",()->Base64.getEncoder().encodeToString(new byte[32]));
		r.add("app.crypto.response-secret",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
		r.add("app.crypto.csrf-secret",()->Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes()));
		r.add("app.crypto.key-directory",()->System.getenv("AUTH_TEST_KEY_DIRECTORY"));
		r.add("app.web.origin",()->ORIGIN);r.add("app.oauth.kakao.redirect-uri",()->ORIGIN+"/api/v1/auth/kakao/callback");
		r.add("app.telemetry.web-version",()->"synthetic-web-v7");
		r.add("app.oauth.kakao.client-id",()->"synthetic-client");r.add("app.oauth.kakao.app-id",()->123);
		r.add("app.gemini.connection-ttl-seconds",()->600);
		r.add("app.gemini.validated-model",()->"models/synthetic-live");r.add("app.gemini.validation-ref",()->"synthetic-test-only");r.add("app.gemini.model-max-seconds",()->600);
		r.add("app.auth.requests-per-minute",()->1000);r.add("app.auth.cleanup-delay-ms",()->3600000);r.add("app.call.worker-delay-ms",()->3600000);
		r.add("app.crypto.discard-delay-ms",()->3600000);
	}
	@TestConfiguration static class Config {
		@Bean @Primary MutableClock testClock(){return new MutableClock();}
		@Bean UserKeyStore testKeys(SecretCrypto crypto){return new FileUserKeyStore(System.getenv("AUTH_TEST_KEY_DIRECTORY"),crypto);}
	}
	static class MutableClock extends Clock {
		AtomicReference<Instant> value=new AtomicReference<>(START);void advance(long seconds){value.updateAndGet(t->t.plusSeconds(seconds));}
		@Override public Instant instant(){return value.get();}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}
	}
	@BeforeEach void reset(){
		for(String table:List.of("keyDiscardJob","deletionJob","webSession","appUser","rateBucket","personaPrompt","promptRelease"))jdbc.update("DELETE FROM `"+table+"`");
		clock.value.set(START);
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("12345","홍길동","MALE",LocalDate.of(2000,1,1),"01012345678"));
		when(gemini.isConfigured()).thenReturn(true);when(gemini.issue(any())).thenReturn("auth_tokens/synthetic-only");
		UUID release=UUID.randomUUID();jdbc.update("INSERT INTO `promptRelease` (`id`,`version`,`status`,`modelId`,`apiVersion`,`safetyInstruction`,`demographicRules`,`guestInstruction`,`validationRef`,`publishedAt`) VALUES (?,1,'PUBLISHED','models/synthetic-live','v1beta','safety','{age} {gender}','neutral','synthetic-test-only',?)",bin(release),time(START.minusSeconds(60)));
		for(String scenario:List.of("FOLLOWED","UNSAFE_TAXI","STRANGER_NEARBY","WALKING_ALONE"))for(String person:List.of("FATHER","MOTHER","FRIEND"))jdbc.update("INSERT INTO `personaPrompt` (`releaseId`,`scenarioCode`,`counterpartCode`,`baseInstruction`,`voiceId`) VALUES (?,?,?,'family conversation','Puck')",bin(release),scenario,person);
	}
	record Result(int status,JsonNode body,HttpHeaders headers){String text(String field){return body.path(field).asString();}}
	class Browser {String cookie,profilePrefillCookie,csrf;String page=crypto.randomToken();}
	Result raw(String method,String path,String body,Browser b,UUID key,String origin,String csrf,String page)throws Exception {
		var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Accept","application/json");
		if(origin!=null)req.header("Origin",origin);if(body!=null)req.header("Content-Type","application/json");
		if(b!=null){var cookies=new ArrayList<String>();if(b.cookie!=null)cookies.add("__Host-safecall-session="+b.cookie);if(b.profilePrefillCookie!=null)cookies.add("__Host-safecall-profile-prefill="+b.profilePrefillCookie);if(!cookies.isEmpty())req.header("Cookie",String.join("; ",cookies));}
		if(csrf!=null)req.header("X-CSRF-Token",csrf);if(page!=null)req.header("X-Call-Page-Key",page);if(key!=null)req.header("Idempotency-Key",key.toString());
		req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
		var response=http.send(req.build(),HttpResponse.BodyHandlers.ofString());
		return new Result(response.statusCode(),response.body().isBlank()?mapper.createObjectNode():mapper.readTree(response.body()),response.headers());
	}
	Result send(String method,String path,Object body,Browser b,UUID key)throws Exception{return raw(method,path,body==null?null:mapper.writeValueAsString(body),b,key,ORIGIN,b==null?null:b.csrf,b==null?null:b.page);}
	void status(Result r,int expected){assertThat(r.status()).as("response: %s",r.body()).isEqualTo(expected);}
	void code(Result r,int expected,String code){status(r,expected);assertThat(r.text("code")).isEqualTo(code);}
	void cookie(Browser b,Result r){for(String value:r.headers().allValues("Set-Cookie")){String content=value.substring(value.indexOf('=')+1,value.indexOf(';'));if(value.startsWith("__Host-safecall-session="))b.cookie=content.isEmpty()?null:content;if(value.startsWith("__Host-safecall-profile-prefill="))b.profilePrefillCookie=content.isEmpty()?null:content;}}
	Browser bootstrap()throws Exception{Browser b=new Browser();var r=send("GET","/api/v1/auth/session",null,b,null);status(r,200);cookie(b,r);b.csrf=r.text("csrfToken");return b;}
	List<Map<String,Object>> decisions(boolean location){return List.of(Map.of("code","PRIVACY_PROCESSING","version",1,"action","GRANTED"),Map.of("code","AI_CALL","version",1,"action","GRANTED"),Map.of("code","LOCATION_PROCESSING","version",1,"action",location?"GRANTED":"DECLINED"));}
	String start(Browser b,String purpose,boolean location)throws Exception{
		var r=send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose",purpose),b,null);status(r,200);
		return Arrays.stream(URI.create(r.text("authorizationUrl")).getRawQuery().split("&")).filter(s->s.startsWith("state=")).findFirst().orElseThrow().substring(6);
	}
	Result callback(Browser b,String state)throws Exception{return send("GET","/api/v1/auth/kakao/callback?state="+state+"&code=synthetic",null,b,null);}
	Result login(Browser b,String purpose,boolean location)throws Exception{var r=callback(b,start(b,purpose,location));status(r,303);cookie(b,r);var session=send("GET","/api/v1/auth/session",null,b,null);cookie(b,session);b.csrf=session.text("csrfToken");return session;}
	Browser member()throws Exception{Browser b=bootstrap();login(b,"LOGIN",false);grant(b,false);return b;}
	void grant(Browser b,boolean location)throws Exception{status(send("POST","/api/v1/me/consents",Map.of("decisions",decisions(location)),b,UUID.randomUUID()),200);}

	Browser guest()throws Exception{Browser b=bootstrap();var r=send("POST","/api/v1/auth/guest",Map.of(),b,null);status(r,200);cookie(b,r);b.csrf=r.text("csrfToken");return b;}
	Map<String,Object> profile(long version){Map<String,Object> body=new HashMap<>();body.put("name","홍길동");body.put("phone","+82 10-1234-5678");body.put("gender",null);body.put("birthDate",null);body.put("isConfirmed",true);body.put("expectedVersion",version);return body;}
	void complete(Browser b)throws Exception{var p=send("GET","/api/v1/me/profile",null,b,null);status(send("PATCH","/api/v1/me/profile",profile(p.body().path("version").asLong()),b,UUID.randomUUID()),200);}
	UUID sessionId(Browser b){return authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).id();}
	UUID userId(Browser b){return authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).userId();}
	Map<String,Object> createBody(){return Map.of("clientCallId",UUID.randomUUID(),"startMode","QUICK","scenarioCode","FOLLOWED","counterpartCode","FATHER","microphonePermission","GRANTED");}
	UUID create(Browser b)throws Exception{var r=send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID());status(r,202);return UUID.fromString(r.text("id"));}
	Result connection(Browser b,UUID id)throws Exception{return send("GET","/api/v1/calls/"+id+"/connection",null,b,null);}
	Map<String,Object> eventBody(Browser b,UUID id,String type,String grant)throws Exception{var c=send("GET","/api/v1/calls/"+id,null,b,null);Map<String,Object> body=new HashMap<>(Map.of("eventId",UUID.randomUUID(),"type",type,"occurredAt",clock.instant().toString(),"expectedVersion",c.body().path("version").asLong()));if(grant!=null)body.put("grantId",grant);return body;}
	Result event(Browser b,UUID id,String type,String grant)throws Exception{return send("POST","/api/v1/calls/"+id+"/events",eventBody(b,id,type,grant),b,UUID.randomUUID());}
	String active(Browser b,UUID id)throws Exception{worker.runOne(id);var g=connection(b,id);status(g,200);String grant=g.text("grantId");status(event(b,id,"CONNECTED",grant),200);status(event(b,id,"RINGING_SHOWN",null),200);status(event(b,id,"ANSWERED",null),200);return grant;}
	Result end(Browser b,UUID id)throws Exception{return send("POST","/api/v1/calls/"+id+"/end",Map.of("reason","TAB_HIDDEN","occurredAt",clock.instant().toString()),b,UUID.randomUUID());}
	long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class);}

	Map<String,Object> telemetryEvent(String category,String code) {
		return new HashMap<>(Map.of("eventId",UUID.randomUUID(),"category",category,"code",code,"occurredAt",clock.instant().toString()));
	}
	Result telemetry(Browser b,List<?> events)throws Exception { return send("POST","/api/v1/telemetry/events",Map.of("events",events),b,null); }

	@Test void guestDailyBudgetSurvivesNewSessionAndReplaysDoNotCharge()throws Exception{
		Browser first=guest();
		for(int i=0;i<20;i++){UUID id=create(first);status(end(first,id),200);clock.advance(15);}
		code(send("POST","/api/v1/calls",createBody(),first,UUID.randomUUID()),429,"RATE_LIMITED");
		Browser next=guest();
		code(send("POST","/api/v1/calls",createBody(),next,UUID.randomUUID()),429,"RATE_LIMITED");
		assertThat(count("callSession")).isEqualTo(20);
		assertThat(jdbc.queryForObject("SELECT usedCount FROM rateBucket WHERE scopeKind='IP' AND operation='CALL_DAY'",Integer.class)).isEqualTo(20);
		clock.value.set(Instant.parse("2026-09-12T15:00:00Z")); // Next KST day.
		var body=createBody();UUID key=UUID.randomUUID();
		var created=send("POST","/api/v1/calls",body,next,key);status(created,202);
		status(send("POST","/api/v1/calls",body,next,key),202);
		status(send("POST","/api/v1/calls",body,next,UUID.randomUUID()),202);
		assertThat(jdbc.queryForObject("SELECT SUM(usedCount) FROM rateBucket WHERE scopeKind='IP' AND operation='CALL_DAY'",Integer.class)).isEqualTo(21);
	}
	@Test void concurrentGuestSessionsShareOneRemainingDailySlot()throws Exception{
		Browser first=guest();
		for(int i=0;i<19;i++){UUID id=create(first);status(end(first,id),200);clock.advance(15);}
		var browsers=List.of(guest(),guest(),guest(),guest());
		var start=new CountDownLatch(1);
		try(var executor=Executors.newFixedThreadPool(4)){
			var futures=new ArrayList<Future<Integer>>();
			for(var b:browsers)futures.add(executor.submit(()->{start.await();return send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID()).status();}));
			start.countDown();var statuses=new ArrayList<Integer>();
			for(var f:futures)statuses.add(f.get(15,TimeUnit.SECONDS));
			assertThat(statuses).containsExactlyInAnyOrder(202,429,429,429);
		}
		assertThat(count("callSession")).isEqualTo(20);
	}
	@Test void spoofedForwardedHeadersCannotResetGuestDailyBudget()throws Exception{
		Browser b=guest();for(int i=0;i<20;i++){UUID id=create(b);status(end(b,id),200);clock.advance(15);}
		Browser next=guest();
		var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/calls"))
			.header("Content-Type","application/json").header("Origin",ORIGIN).header("Cookie","__Host-safecall-session="+next.cookie)
			.header("X-CSRF-Token",next.csrf).header("X-Call-Page-Key",next.page).header("Idempotency-Key",UUID.randomUUID().toString())
			.header("X-Forwarded-For","203.0.113.99").header("Forwarded","for=203.0.113.99")
			.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(createBody()))).build();
		assertThat(http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
	}
	@Test void failedKeyDiscardRetainsDurableReferenceAndRestartedWorkerRetries()throws Exception{
		Browser b=guest();UUID id=create(b);worker.runOne(id);var grant=connection(b,id);
		String ref=jdbc.queryForObject("SELECT keyRef FROM connectionGrant",String.class);
		doThrow(new IllegalStateException("synthetic failure")).doCallRealMethod().when(userKeys).discard(ref);
		status(event(b,id,"CONNECTED",grant.text("grantId")),200);
		assertThat(jdbc.queryForObject("SELECT keyRef FROM connectionGrant",String.class)).isNull();
		verify(userKeys,never()).discard(ref);discardQueue.run();
		assertThat(count("keyDiscardJob")).isEqualTo(1);
		assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isTrue();
		clock.advance(30);new KeyDiscardQueue(jdbc,userKeys,clock,transactionManager,creationJournal).run();
		assertThat(count("keyDiscardJob")).isZero();
		assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isFalse();
		discardQueue.run();verify(userKeys,times(2)).discard(ref);
	}
	@Test void durableDiscardSurvivesMissingCallbackAndAlreadyRemovedKey()throws Exception{
		String ref=userKeys.create(UUID.randomUUID());
		var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		tx.executeWithoutResult(s->{discardQueue.enqueue(ref);discardQueue.enqueue(ref);});
		// Simulate process exit after DB commit: no afterCommit callback was registered.
		userKeys.discard(ref);
		try(var executor=Executors.newFixedThreadPool(2)){
			var a=executor.submit(()->new KeyDiscardQueue(jdbc,userKeys,clock,transactionManager,creationJournal).run());
			var b=executor.submit(()->new KeyDiscardQueue(jdbc,userKeys,clock,transactionManager,creationJournal).run());
			a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
		}
		assertThat(count("keyDiscardJob")).isZero();
	}
	@Test void rolledBackDiscardIntentNeverDeletesLiveKey(){
		String ref=userKeys.create(UUID.randomUUID());
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s->{transientKeys.discardAfterCommit(ref);s.setRollbackOnly();});
		discardQueue.run();assertThat(count("keyDiscardJob")).isZero();assertThat(userKeys.read(ref)).hasSize(32);
	}
	@Test void failedRollbackKeyDiscardAlsoHasDurableRetry(){
		var captured=new AtomicReference<String>();
		doThrow(new IllegalStateException("synthetic failure")).doCallRealMethod().when(userKeys).discard(anyString());
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s->{captured.set(transientKeys.create(UUID.randomUUID()));s.setRollbackOnly();});
		assertThat(count("keyDiscardJob")).isEqualTo(1);
		clock.advance(30);discardQueue.run();assertThat(count("keyDiscardJob")).isZero();
		assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),captured.get()))).isFalse();
	}

@Test void failingDiscardBatchDoesNotStarveLaterJobs(){
		var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		doThrow(new IllegalStateException("synthetic permanent failure")).when(userKeys).discard(startsWith("blocked-"));
		clock.advance(-1);
		tx.executeWithoutResult(s->{for(int i=0;i<100;i++)discardQueue.enqueue("blocked-"+i);});
		clock.advance(1);String ref=userKeys.create(UUID.randomUUID());
		tx.executeWithoutResult(s->discardQueue.enqueue(ref));
		discardQueue.run();assertThat(count("keyDiscardJob")).isEqualTo(101);
		// Failed jobs are delayed; the next due batch must reach the later healthy key.
		discardQueue.run();assertThat(count("keyDiscardJob")).isEqualTo(100);
		assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isFalse();
	}
	@Test void historyDeletionKeepsHomeAndComposerEligibleThroughCompletion()throws Exception{
		Browser b=composerMember();status(deletion(b,"USAGE_HISTORY",UUID.randomUUID()),202);
		assertThat(send("GET","/api/v1/home",null,b,null).body().path("isMessageComposeEligible").asBoolean()).isTrue();status(composer(b,"?mode=SAFETY"),200);
		clock.advance(60);historyCleanup.run();
		assertThat(send("GET","/api/v1/home",null,b,null).body().path("isMessageComposeEligible").asBoolean()).isTrue();status(composer(b,"?mode=SAFETY"),200);
	}
	@Test void homeAndComposerAgreeForEveryDeletionScopeAndStatus()throws Exception{
		Browser b=composerMember();var job=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(job,202);
		for(String scope:List.of("ACCOUNT","AI_DATA","LOCATION_DATA","USAGE_HISTORY")){
			for(String state:List.of("PENDING","PROCESSING","FAILED","COMPLETED")){
				jdbc.update("UPDATE deletionJob SET scope=?,status=?,accountSubjectHash=?,completedAt=? WHERE id=?",scope,state,
					scope.equals("ACCOUNT")&&!state.equals("COMPLETED")?crypto.hash("TEST_SUBJECT","synthetic"):null,
					state.equals("COMPLETED")?time(clock.instant()):null,bin(UUID.fromString(job.text("id"))));
				boolean eligible=scope.equals("USAGE_HISTORY")||Set.of("FAILED","COMPLETED").contains(state);
				var home=send("GET","/api/v1/home",null,b,null);status(home,200);
				assertThat(home.body().path("isMessageComposeEligible").asBoolean()).as(scope+" "+state).isEqualTo(eligible);
				status(composer(b,"?mode=SAFETY"),eligible?200:409);
			}
		}
	}
	@Test void callEventAndEndRejectInvalidTimesWithoutChangingState()throws Exception{
		Browser b=guest();UUID id=create(b);
		for(Object invalid:List.of(0,"2026-09-12T00:00:00","2026-02-30T00:00:00Z","+10000-01-01T00:00:00Z","0999-12-31T23:59:59Z","1000-01-01T00:00:00+01:00","9999-12-31T23:59:59-01:00")){
			var event=new HashMap<>(eventBody(b,id,"FAILED",null));event.put("errorCode","CONNECTION_FAILED");event.put("occurredAt",invalid);
			status(send("POST","/api/v1/calls/"+id+"/events",event,b,UUID.randomUUID()),400);
			status(send("POST","/api/v1/calls/"+id+"/end",Map.of("reason","USER_ENDED","occurredAt",invalid),b,UUID.randomUUID()),400);
		}
		assertThat(send("GET","/api/v1/calls/"+id,null,b,null).text("state")).isEqualTo("PREPARING");
		assertThat(count("callEvent")).isEqualTo(2);
		status(end(b,id),200);
	}
	@Test void callTimesNormalizeOffsetsAndMicrosecondsAtStorageBoundary()throws Exception{
		Browser b=guest();UUID id=create(b);
		var body=new HashMap<>(eventBody(b,id,"FAILED",null));body.put("errorCode","CONNECTION_FAILED");body.put("occurredAt","1000-01-01T01:00:00+01:00");
		status(send("POST","/api/v1/calls/"+id+"/events",body,b,UUID.randomUUID()),200);
		UUID other=create(b);
		status(send("POST","/api/v1/calls/"+other+"/end",Map.of("reason","USER_ENDED","occurredAt","9999-12-31T23:59:59.999999999Z"),b,UUID.randomUUID()),200);
		assertThat(jdbc.queryForObject("SELECT occurredAt FROM callEvent WHERE callId=? AND eventType='ENDED'",java.time.LocalDateTime.class,bin(other))).isEqualTo(java.time.LocalDateTime.parse("9999-12-31T23:59:59.999999"));
	}

	Browser personalizedMember(String gender,String birth)throws Exception{
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("12345","홍길동",gender,LocalDate.parse(birth),"01012345678"));
		Browser b=member();var p=send("GET","/api/v1/me/profile",null,b,null);
		var body=profile(p.body().path("version").asLong());body.put("gender",gender);body.put("birthDate",birth);
		status(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),200);
		// Existing accounts may retain verified Kakao demographics from before the migration.
		jdbc.update("UPDATE `appUser` SET `genderSource`='KAKAO',`birthDateSource`='KAKAO' WHERE `id`=?",bin(userId(b)));
		return b;
	}
	@Test void delayedInitialIssuanceStillReturnsUsableToken()throws Exception{
		Browser b=guest();UUID id=create(b);clock.advance(11);worker.runOne(id);
		verify(gemini,times(1)).issue(any());status(connection(b,id),200);
		assertThat(jdbc.queryForObject("SELECT status FROM connectionGrant WHERE callId=?",String.class,bin(id))).isEqualTo("READY");
	}
	@Test void delayedClaimGetsFullTimeoutAndReaperExpiresAtBoundary()throws Exception{
		Browser b=guest();UUID id=create(b);clock.advance(11);assertThat(calls.claim(id)).isNotNull();
		clock.advance(9);assertThat(callRepository.expired(clock.instant())).doesNotContain(id);
		clock.advance(1);assertThat(callRepository.expired(clock.instant())).contains(id);
		calls.reap(id);code(connection(b,id),503,"CONNECTION_ISSUE_UNKNOWN");
		verify(gemini,never()).issue(any());
	}
	@Test void legacyIssuingWithoutStartTimeFailsClosed()throws Exception{
		Browser b=guest();UUID id=create(b);assertThat(calls.claim(id)).isNotNull();
		jdbc.update("UPDATE connectionGrant SET issuingStartedAt=NULL WHERE callId=?",bin(id));
		calls.reap(id);code(connection(b,id),503,"CONNECTION_ISSUE_UNKNOWN");
		verify(gemini,never()).issue(any());
	}
	@Test void concurrentKeyCreationSucceedsWithEveryBusinessConnectionOccupied()throws Exception{
		var config=new com.zaxxer.hikari.HikariConfig();
		config.setJdbcUrl(System.getenv("AUTH_TEST_DB_URL"));config.setUsername("root");config.setPassword("");
		config.setMaximumPoolSize(4);config.setMinimumIdle(4);config.setConnectionTimeout(3000);
		config.setPoolName("concurrent-business-test");
		try(var pool=new com.zaxxer.hikari.HikariDataSource(config);var executor=Executors.newFixedThreadPool(4)){
			var journal=new KeyCreationJournal(pool);
			try{
				var reviewJdbc=new JdbcTemplate(pool);
				var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool);
				var queue=new KeyDiscardQueue(reviewJdbc,userKeys,clock,manager,journal);
				var keys=new TransientKeys(userKeys,queue);
				var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
				var ready=new CountDownLatch(4);var start=new CountDownLatch(1);
				var futures=new ArrayList<Future<String>>();
				try{
					for(int i=0;i<4;i++)futures.add(executor.submit(()->tx.execute(s->{
						reviewJdbc.queryForObject("SELECT 1",Integer.class);ready.countDown();
						try{if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
						return keys.create(UUID.randomUUID());
					})));
					assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
				}finally{start.countDown();}
				for(var future:futures){String ref=future.get(10,TimeUnit.SECONDS);assertThat(userKeys.read(ref)).hasSize(32);userKeys.discard(ref);}
				assertThat(count("keyDiscardJob")).isZero();
			}finally{journal.close();}
		}
	}
	@Test void queueInsertFailurePreventsExternalKeyCreation(){
		jdbc.execute("CREATE TRIGGER review_queue_failure BEFORE INSERT ON keyDiscardJob FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic queue outage'");
		try{
			assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s->transientKeys.create(UUID.randomUUID())))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
		}finally{jdbc.execute("DROP TRIGGER review_queue_failure");}
		verify(userKeys,never()).create(any());assertThat(count("keyDiscardJob")).isZero();
	}
	@Test void rollbackDoesNotNeedAnotherQueueInsertAndFailedDiscardSurvivesRestart(){
		var ref=new AtomicReference<String>();
		doThrow(new IllegalStateException("synthetic store outage")).doCallRealMethod().when(userKeys).discard(anyString());
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s->{
			ref.set(transientKeys.create(UUID.randomUUID()));
			doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic queue outage")).when(discardQueue).enqueue(anyString());
			s.setRollbackOnly();
		});
		verify(userKeys).discard(ref.get());assertThat(count("keyDiscardJob")).isEqualTo(1);
		clock.advance(30);new KeyDiscardQueue(jdbc,userKeys,clock,transactionManager,creationJournal).run();
		assertThat(count("keyDiscardJob")).isZero();
		assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref.get()))).isFalse();
	}
	@Test void creationJournalRecoversWithoutTransactionCallbackAndKeepsCommittedKeys(){
		UUID allocation=UUID.randomUUID();String ref=userKeys.reference(allocation);
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s->{
			UUID job=discardQueue.prepareCreation(ref);discardQueue.enlistCreation(job);userKeys.create(allocation);
			// Model process death: rollback occurs but no TransientKeys callback is registered.
			s.setRollbackOnly();
		});
		assertThat(count("keyDiscardJob")).isEqualTo(1);clock.advance(30);
		new KeyDiscardQueue(jdbc,userKeys,clock,transactionManager,creationJournal).run();
		assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isFalse();
		String retained=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->transientKeys.create(UUID.randomUUID()));
		clock.advance(30);discardQueue.run();assertThat(count("keyDiscardJob")).isZero();assertThat(userKeys.read(retained)).hasSize(32);
	}
	@Test void cleanupCannotDeleteKeyDuringCreationTransaction()throws Exception{
		var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);var ref=new AtomicReference<String>();
		var ready=new CountDownLatch(1);var commit=new CountDownLatch(1);
		try(var executor=Executors.newFixedThreadPool(2)){
			var owner=executor.submit(()->tx.executeWithoutResult(s->{
				ref.set(transientKeys.create(UUID.randomUUID()));ready.countDown();
				try{if(!commit.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
			}));
			assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();clock.advance(30);
			try{executor.submit(discardQueue::run).get(5,TimeUnit.SECONDS);assertThat(userKeys.read(ref.get())).hasSize(32);}
			finally{commit.countDown();}
			owner.get(10,TimeUnit.SECONDS);
		}
		discardQueue.run();assertThat(userKeys.read(ref.get())).hasSize(32);assertThat(count("keyDiscardJob")).isZero();
	}
	long authSuccesses(){return jdbc.queryForObject("SELECT COUNT(*) FROM operationEvent WHERE code='AUTH_SUCCEEDED'",Long.class);}
	org.springframework.scheduling.TaskScheduler schedulerFor(Object bean,String method)throws Exception{
		var type=org.springframework.aop.support.AopUtils.getTargetClass(bean);
		var scheduling=type.getMethod(method).getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
		return applicationContext.getBean(scheduling.scheduler(),org.springframework.scheduling.TaskScheduler.class);
	}
	void awaitReady(UUID call){
		org.awaitility.Awaitility.await().atMost(5,TimeUnit.SECONDS).untilAsserted(()->
			assertThat(jdbc.queryForObject("SELECT status FROM connectionGrant WHERE callId=? ORDER BY generation DESC LIMIT 1",String.class,bin(call))).isEqualTo("READY"));
	}
	@Test void blockedAccountCleanupDoesNotDelayCallIssuanceOrExpiry()throws Exception{
		Browser member=member();login(member,"REAUTH",false);status(deletion(member,"ACCOUNT",UUID.randomUUID()),202);clock.advance(60);accountsCleanup.run();
		Browser old=guest();UUID expired=create(old);clock.advance(31);Browser current=guest();UUID fresh=create(current);
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
		doAnswer(inv->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return null;}).when(unlink).unlink("12345");
		var cleanup=schedulerFor(externalCleanup,"run").schedule(externalCleanup::run,Instant.now());
		try{
			assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
			schedulerFor(worker,"tick").schedule(worker::tick,Instant.now()).get(3,TimeUnit.SECONDS);
			awaitReady(fresh);assertThat(connection(current,fresh).status()).isEqualTo(200);
			assertThat(send("GET","/api/v1/calls/"+expired,null,old,null).text("endReason")).isEqualTo("SESSION_EXPIRED");
			assertThat(cleanup.isDone()).isFalse();
		}finally{release.countDown();cleanup.get(5,TimeUnit.SECONDS);}
	}
	@Test void blockedKeyDisposalDoesNotBlockExpiryCommitOrNewCall()throws Exception{
		Browser old=guest();UUID expired=create(old);worker.runOne(expired);
		String expiredRef=jdbc.queryForObject("SELECT keyRef FROM connectionGrant WHERE callId=?",String.class,bin(expired));
		clock.advance(31);Browser current=guest();UUID fresh=create(current);
		String blockedRef=userKeys.create(UUID.randomUUID());
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->discardQueue.enqueue(blockedRef));
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
		doAnswer(inv->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return inv.callRealMethod();}).when(userKeys).discard(blockedRef);
		var cleanup=schedulerFor(discardQueue,"run").schedule(discardQueue::run,Instant.now());
		try{
			assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
			schedulerFor(worker,"tick").schedule(worker::tick,Instant.now()).get(3,TimeUnit.SECONDS);
			awaitReady(fresh);
			assertThat(jdbc.queryForObject("SELECT state FROM callSession WHERE id=?",String.class,bin(expired))).isEqualTo("ENDED");
			assertThat(jdbc.queryForObject("SELECT keyRef FROM connectionGrant WHERE callId=?",String.class,bin(expired))).isNull();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM keyDiscardJob WHERE keyRef=?",Integer.class,expiredRef)).isEqualTo(1);
			verify(userKeys,never()).discard(expiredRef);assertThat(cleanup.isDone()).isFalse();
		}finally{release.countDown();cleanup.get(5,TimeUnit.SECONDS);}
		discardQueue.run();assertThatThrownBy(()->userKeys.read(expiredRef)).isInstanceOf(RuntimeException.class);
	}
	@Test void successfulAuthenticationIsRecordedOncePerNewAuthenticatedSession()throws Exception{
		Browser anonymous=bootstrap();assertThat(authSuccesses()).isZero();
		Browser b=member();assertThat(authSuccesses()).isEqualTo(1);
		complete(b);send("GET","/api/v1/auth/session",null,b,null);assertThat(authSuccesses()).isEqualTo(1);
		String state=start(b,"REAUTH",false);var result=callback(b,state);status(result,303);cookie(b,result);
		assertThat(authSuccesses()).isEqualTo(2);callback(b,state);assertThat(authSuccesses()).isEqualTo(2);
		Browser guest=guest();assertThat(authSuccesses()).isEqualTo(3);
		status(send("POST","/api/v1/auth/guest",Map.of(),guest,null),200);
		status(send("GET","/api/v1/auth/session",null,guest,null),200);assertThat(authSuccesses()).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operationEvent WHERE code='AUTH_SUCCEEDED' AND isSuccess=1",Long.class)).isEqualTo(3);
	}
	@Test void authenticationEventFailureRollsBackSessionIssuance()throws Exception{
		Browser b=bootstrap();
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic")).when(authRepository).observe(any(),eq("AUTH"),eq("AUTH_SUCCEEDED"),eq(true),any());
		status(send("POST","/api/v1/auth/guest",Map.of(),b,null),500);
		assertThat(authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).status()).isEqualTo("ACTIVE");
		assertThat(authSuccesses()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webSession WHERE kind='GUEST'",Long.class)).isZero();
	}
	long telemetryCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM `operationEvent` WHERE `category`<>'AUTH'",Long.class); }
	int telemetryRate() { return jdbc.queryForObject("SELECT COALESCE(SUM(`usedCount`),0) FROM `rateBucket` WHERE `operation`='TELEMETRY'",Integer.class); }
	void eventCounts(Result result,int accepted,int duplicate) {
		status(result,202);assertThat(result.body().properties()).hasSize(2);
		assertThat(result.body().path("acceptedCount").asInt()).isEqualTo(accepted);
		assertThat(result.body().path("duplicateCount").asInt()).isEqualTo(duplicate);
	}
	@Test void telemetryAcceptsGuestsAndMembersBeforeOnboardingWithoutChangingState()throws Exception {
		Browser member=member(),guest=guest();long version=authRepository.user(userId(member),false).version();
		var event=telemetryEvent("MESSAGE_COMPOSER","COMPOSER_OPENED");event.put("isSuccess",true);event.put("latencyMs",3600000);event.put("networkType","UNKNOWN");
		for(Browser browser:List.of(member,guest)) {
			var result=telemetry(browser,List.of(event));eventCounts(result,1,0);
			assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
		}
		assertThat(telemetryCount()).isEqualTo(2);assertThat(telemetryRate()).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT `webVersion` FROM `operationEvent` WHERE `category`<>'AUTH'",String.class)).containsOnly("synthetic-web-v7");
		assertThat(authRepository.user(userId(member),false).version()).isEqualTo(version);assertThat(count("callSession")).isZero();
	}
	@Test void telemetryRequiresAuthenticatedSessionOriginAndCsrf()throws Exception {
		Browser anonymous=bootstrap(),b=guest();var batch=List.of(telemetryEvent("SOS","SOS_GUIDE_VIEWED"));String body=mapper.writeValueAsString(Map.of("events",batch));
		code(telemetry(anonymous,batch),401,"AUTHENTICATION_REQUIRED");code(telemetry(null,batch),403,"CSRF_INVALID");
		code(raw("POST","/api/v1/telemetry/events",body,b,null,null,b.csrf,null),403,"ORIGIN_NOT_ALLOWED");
		code(raw("POST","/api/v1/telemetry/events",body,b,null,ORIGIN,"wrong",null),403,"CSRF_INVALID");
		status(send("POST","/api/v1/auth/logout",Map.of(),b,null),204);status(telemetry(b,batch),401);
		assertThat(telemetryCount()).isZero();assertThat(telemetryRate()).isZero();
	}
	@Test void telemetryRejectsExpiredAndAccountDeletionPendingSessions()throws Exception {
		Browser b=guest();var batch=List.of(telemetryEvent("FALLBACK","FALLBACK_STARTED"));
		clock.advance(86400);code(telemetry(b,batch),401,"SESSION_EXPIRED");
		Browser member=member();login(member,"REAUTH",false);status(deletion(member,"ACCOUNT",UUID.randomUUID()),202);
		code(telemetry(member,batch),409,"ACCOUNT_DELETION_PENDING");assertThat(telemetryCount()).isZero();
	}
	@Test void telemetryAcceptsEveryPublicCodeAndRejectsServerOnlyOrMismatchedCodes()throws Exception {
		Browser b=guest();var codes=Map.of(
			"PERMISSION",List.of("MICROPHONE_PERMISSION_REVIEWED","LOCATION_PERMISSION_REVIEWED","PERMISSION_QUERY_UNAVAILABLE"),
			"CALL",List.of("LIVE_CONNECT_STARTED","LIVE_CONNECT_SUCCEEDED","LIVE_CONNECT_FAILED","RINGING_SHOWN","RINGING_FAILED","PAGE_EXITED","PAGE_RELOADED"),
			"AUDIO",List.of("FIRST_AUDIO_PLAYED","AUDIO_INTERRUPTED"),"GESTURE",List.of("QUICK_START_SELECTED","QUICK_START_CANCELLED"),
			"LOCATION",List.of("LOCATION_AVAILABLE","LOCATION_UNAVAILABLE"),"MESSAGE_COMPOSER",List.of("COMPOSER_OPENED","COMPOSER_OPEN_FAILED"),
			"SOS",List.of("SOS_GUIDE_VIEWED","SOS_GUIDE_FAILED"),"FALLBACK",List.of("FALLBACK_STARTED","FALLBACK_ENDED"));
		for(var category:codes.entrySet()) {
			var batch=category.getValue().stream().map(code->telemetryEvent(category.getKey(),code)).toList();eventCounts(telemetry(b,batch),batch.size(),0);
		}
		for(var invalid:List.of(telemetryEvent("AUTH","AUTH_SUCCEEDED"),telemetryEvent("CALL","COMPOSER_OPENED"),telemetryEvent("CUSTOM","CUSTOM")))
			code(telemetry(b,List.of(telemetryEvent("SOS","SOS_GUIDE_VIEWED"),invalid)),422,"INVALID_EVENT");
		assertThat(telemetryCount()).isEqualTo(22);assertThat(telemetryRate()).isEqualTo(22);
	}
	@Test void telemetryValidatesOutcomeMeaningAndNetworkAllowlist()throws Exception {
		Browser b=guest();
		for(var pair:List.of(List.of("CALL","LIVE_CONNECT_SUCCEEDED"),List.of("CALL","RINGING_SHOWN"),List.of("AUDIO","FIRST_AUDIO_PLAYED"),List.of("LOCATION","LOCATION_AVAILABLE"),List.of("MESSAGE_COMPOSER","COMPOSER_OPENED"),List.of("SOS","SOS_GUIDE_VIEWED"))) {
			var event=telemetryEvent(pair.get(0),pair.get(1));event.put("isSuccess",false);code(telemetry(b,List.of(event)),422,"INVALID_EVENT");
			event.put("isSuccess",true);eventCounts(telemetry(b,List.of(event)),1,0);
		}
		for(var pair:List.of(List.of("PERMISSION","PERMISSION_QUERY_UNAVAILABLE"),List.of("CALL","LIVE_CONNECT_FAILED"),List.of("CALL","RINGING_FAILED"),List.of("LOCATION","LOCATION_UNAVAILABLE"),List.of("MESSAGE_COMPOSER","COMPOSER_OPEN_FAILED"),List.of("SOS","SOS_GUIDE_FAILED"))) {
			var event=telemetryEvent(pair.get(0),pair.get(1));event.put("isSuccess",true);code(telemetry(b,List.of(event)),422,"INVALID_EVENT");
			event.put("isSuccess",false);eventCounts(telemetry(b,List.of(event)),1,0);
		}
		for(String network:List.of("WIFI","CELLULAR","OFFLINE","UNKNOWN","5G")) {
			var event=telemetryEvent("CALL","PAGE_EXITED");event.put("networkType",network);
			if(network.equals("5G"))code(telemetry(b,List.of(event)),422,"INVALID_EVENT");else eventCounts(telemetry(b,List.of(event)),1,0);
		}
		// 사용자 끼어들기 등 정상 중단도 있으므로 오디오 중단 자체를 실패로 단정하지 않는다.
		for(Boolean outcome:Arrays.asList(true,false,null)) {
			var event=telemetryEvent("AUDIO","AUDIO_INTERRUPTED");event.put("isSuccess",outcome);eventCounts(telemetry(b,List.of(event)),1,0);
		}
	}
	@Test void telemetryRejectsInvalidShapesAndNeverCoercesIntegerBooleanOrTimestamp()throws Exception {
		Browser b=guest();var valid=telemetryEvent("CALL","LIVE_CONNECT_STARTED");
		for(List<?> batch:List.of(List.of(),Collections.nCopies(21,valid),Arrays.asList(valid,null)))status(telemetry(b,batch),400);
		for(String field:List.of("eventId","category","code","occurredAt")) {
			var event=new HashMap<>(valid);event.remove(field);status(telemetry(b,List.of(event)),400);
		}
		for(var change:List.of(Map.entry("latencyMs",(Object)(-1)),Map.entry("latencyMs",3600001),Map.entry("latencyMs",0.5),Map.entry("latencyMs","5"),Map.entry("isSuccess","true"),Map.entry("isSuccess",1),Map.entry("occurredAt","invalid"),Map.entry("occurredAt","2026-09-12T00:00:00"),Map.entry("occurredAt","0999-12-31T00:00:00Z"),Map.entry("category","X".repeat(17)),Map.entry("code","X".repeat(49)),Map.entry("networkType","X".repeat(9)))) {
			var event=new HashMap<>(valid);event.put(change.getKey(),change.getValue());status(telemetry(b,List.of(event)),400);
		}
		status(send("POST","/api/v1/telemetry/events",Map.of(),b,null),400);
		for(Object timestamp:List.of(0,0.5,"2026-09-12T00:00:00+09:00:01","2026-09-12T00:00:00.1234567890Z","2026-02-30T00:00:00Z")) {
			var event=new HashMap<>(valid);event.put("occurredAt",timestamp);status(telemetry(b,List.of(event)),400);
		}
		assertThat(telemetryCount()).isZero();assertThat(telemetryRate()).isZero();
	}
	@Test void telemetryRejectsPrivateAndServerOwnedFieldsWithoutReflectingTheirValues()throws Exception {
		Browser b=guest();
		for(String field:List.of("sessionId","userId","webVersion","name","phone","latitude","longitude","accuracy","url","token","handle","payload","errorMessage","fingerprint","audio","transcript")) {
			var event=telemetryEvent("CALL","LIVE_CONNECT_STARTED");event.put(field,"private-synthetic-value");
			var result=telemetry(b,List.of(event));status(result,400);assertThat(result.body().toString()).doesNotContain("private-synthetic-value");
		}
		status(send("POST","/api/v1/telemetry/events",Map.of("events",List.of(telemetryEvent("SOS","SOS_GUIDE_VIEWED")),"userId","private"),b,null),400);
		assertThat(telemetryCount()).isZero();
	}
	@Test void telemetryReplaysWithinBatchAcrossTimezonesAndDeployments()throws Exception {
		Browser b=guest();var event=telemetryEvent("CALL","PAGE_EXITED");event.put("occurredAt","2026-09-12T00:00:00.123456789Z");
		eventCounts(telemetry(b,List.of(event,event)),1,1);
		event.put("callId",null);event.put("isSuccess",null);event.put("latencyMs",null);event.put("networkType",null);
		event.put("occurredAt","2026-09-12T09:00:00.123456+09:00");doReturn("next-deployment").when(telemetryPolicy).webVersion();
		eventCounts(telemetry(b,List.of(event)),0,1);assertThat(telemetryRate()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT `webVersion` FROM `operationEvent` WHERE `category`='CALL'",String.class)).isEqualTo("synthetic-web-v7");
	}
	@Test void telemetryConflictsCompareEveryAllowedFieldAndRollbackWholeBatch()throws Exception {
		Browser b=guest();UUID call=create(b);var event=telemetryEvent("CALL","LIVE_CONNECT_STARTED");eventCounts(telemetry(b,List.of(event)),1,0);
		for(var change:List.of(Map.entry("callId",(Object)call),Map.entry("code","PAGE_RELOADED"),Map.entry("isSuccess",true),Map.entry("latencyMs",0),Map.entry("networkType","UNKNOWN"),Map.entry("occurredAt",START.plusSeconds(1).toString()))) {
			var changed=new HashMap<>(event);changed.put(change.getKey(),change.getValue());
			code(telemetry(b,List.of(telemetryEvent("SOS","SOS_GUIDE_VIEWED"),changed)),409,"IDEMPOTENCY_CONFLICT");
		}
		var changed=new HashMap<>(event);changed.put("category","FALLBACK");changed.put("code","FALLBACK_STARTED");
		code(telemetry(b,List.of(changed)),409,"IDEMPOTENCY_CONFLICT");
		var newEvent=telemetryEvent("CALL","PAGE_RELOADED");var conflict=new HashMap<>(newEvent);conflict.put("latencyMs",1);
		code(telemetry(b,List.of(newEvent,conflict)),409,"IDEMPOTENCY_CONFLICT");
		assertThat(telemetryCount()).isEqualTo(1);assertThat(telemetryRate()).isEqualTo(1);
		assertThat(send("GET","/api/v1/calls/"+call,null,b,null).text("state")).isEqualTo("PREPARING");
	}
	@Test void telemetryCallOwnershipIsSessionScopedEvenForTheSameMemberAndTerminalCalls()throws Exception {
		Browser owner=member();complete(owner);UUID call=create(owner);Browser other=member();
		assertThat(userId(owner)).isEqualTo(userId(other));var event=telemetryEvent("CALL","LIVE_CONNECT_SUCCEEDED");event.put("callId",call);event.put("isSuccess",true);
		code(telemetry(other,List.of(telemetryEvent("CALL","PAGE_EXITED"),event)),404,"RESOURCE_NOT_FOUND");
		event.put("callId",UUID.randomUUID());code(telemetry(owner,List.of(event)),404,"RESOURCE_NOT_FOUND");event.put("callId",call);
		eventCounts(telemetry(owner,List.of(event)),1,0);status(end(owner,call),200);
		var ended=telemetryEvent("CALL","PAGE_EXITED");ended.put("callId",call);eventCounts(telemetry(owner,List.of(ended)),1,0);
		assertThat(send("GET","/api/v1/calls/"+call,null,owner,null).text("state")).isEqualTo("ENDED");
	}
	@Test void telemetryConcurrentIdenticalAndConflictingRequestsAreAtomic()throws Exception {
		Browser b=guest();var event=telemetryEvent("CALL","PAGE_EXITED");
		try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var ready=new CountDownLatch(1);
			var first=executor.submit(()->{ready.await();return telemetry(b,List.of(event));});var second=executor.submit(()->{ready.await();return telemetry(b,List.of(event));});ready.countDown();
			var a=first.get(10,TimeUnit.SECONDS);var c=second.get(10,TimeUnit.SECONDS);status(a,202);status(c,202);
			assertThat(a.body().path("acceptedCount").asInt()+c.body().path("acceptedCount").asInt()).isEqualTo(1);
			var next=telemetryEvent("CALL","PAGE_EXITED");var changed=new HashMap<>(next);changed.put("latencyMs",1);var start=new CountDownLatch(1);
			var left=executor.submit(()->{start.await();return telemetry(b,List.of(next));});var right=executor.submit(()->{start.await();return telemetry(b,List.of(changed));});start.countDown();
			assertThat(List.of(left.get(10,TimeUnit.SECONDS).status(),right.get(10,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(202,409);
		}
		assertThat(telemetryCount()).isEqualTo(2);assertThat(telemetryRate()).isEqualTo(2);
	}
	@Test void telemetryRateCountsNewEventsOnlyAndResetsAtServerMinute()throws Exception {
		Browser b=guest();List<Map<String,Object>> last=null;
		for(int batch=0;batch<6;batch++) {last=new ArrayList<>();for(int i=0;i<20;i++)last.add(telemetryEvent("CALL","PAGE_EXITED"));eventCounts(telemetry(b,last),20,0);}
		eventCounts(telemetry(b,last),0,20);clock.advance(10);
		var blocked=telemetry(b,List.of(last.getFirst(),telemetryEvent("CALL","PAGE_EXITED")));code(blocked,429,"RATE_LIMITED");
		assertThat(blocked.headers().firstValue("Retry-After")).contains("50");assertThat(telemetryCount()).isEqualTo(120);assertThat(telemetryRate()).isEqualTo(120);
		eventCounts(telemetry(guest(),List.of(last.getFirst())),1,0);
		clock.advance(50);eventCounts(telemetry(b,List.of(last.getFirst(),telemetryEvent("CALL","PAGE_EXITED"))),1,1);
	}
	@Test void telemetryInsertFailureRollsBackPreviousInsertsAndRateCharge()throws Exception {
		Browser b=guest();
		jdbc.execute("CREATE TRIGGER telemetry_failure BEFORE INSERT ON `operationEvent` FOR EACH ROW BEGIN IF NEW.`code`='COMPOSER_OPEN_FAILED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic'; END IF; END");
		try {code(telemetry(b,List.of(telemetryEvent("CALL","PAGE_EXITED"),telemetryEvent("MESSAGE_COMPOSER","COMPOSER_OPEN_FAILED"))),500,"INTERNAL_SERVER_ERROR");}
		finally {jdbc.execute("DROP TRIGGER telemetry_failure");}
		assertThat(telemetryCount()).isZero();assertThat(telemetryRate()).isZero();
	}
	@Test void telemetryFollowsHistoryAccountAndSessionDeletion()throws Exception {
		Browser b=member();complete(b);UUID call=create(b);var event=telemetryEvent("CALL","PAGE_EXITED");event.put("callId",call);
		eventCounts(telemetry(b,List.of(event,telemetryEvent("SOS","SOS_GUIDE_VIEWED"))),2,0);status(end(b,call),200);
		status(deletion(b,"USAGE_HISTORY",UUID.randomUUID()),202);clock.advance(60);historyCleanup.run();assertThat(telemetryCount()).isEqualTo(1);
		eventCounts(telemetry(b,List.of(telemetryEvent("SOS","SOS_GUIDE_VIEWED"))),1,0);
		login(b,"REAUTH",false);status(deletion(b,"ACCOUNT",UUID.randomUUID()),202);clock.advance(60);accountsCleanup.run();assertThat(telemetryCount()).isZero();assertThat(telemetryRate()).isZero();
		Browser guest=guest();eventCounts(telemetry(guest,List.of(telemetryEvent("SOS","SOS_GUIDE_VIEWED"))),1,0);
		status(send("POST","/api/v1/auth/logout",Map.of(),guest,null),204);clock.advance(3600);retention.run();assertThat(telemetryCount()).isZero();assertThat(telemetryRate()).isZero();
	}
	@Test void telemetryRetentionUsesRecordedTimeAndOpenApiDocuments202AndSessionProtection()throws Exception {
		Browser b=member();var event=telemetryEvent("CALL","PAGE_EXITED");event.put("occurredAt","2000-01-01T00:00:00Z");eventCounts(telemetry(b,List.of(event)),1,0);
		clock.advance(1209599);retention.run();assertThat(telemetryCount()).isEqualTo(1);clock.advance(1);retention.run();assertThat(telemetryCount()).isZero();
		var operation=send("GET","/v3/api-docs",null,null,null).body().path("paths").path("/api/v1/telemetry/events").path("post");
		assertThat(operation.path("operationId").asString()).isEqualTo("O01");assertThat(operation.path("responses").has("202")).isTrue();
		assertThat(operation.path("security").toString()).contains("webSession");
		assertThat(operation.path("parameters").toString()).contains("X-CSRF-Token","Origin").doesNotContain("Idempotency-Key");
	}

	@Test void anonymousCookieAndStableCsrfArePrivate()throws Exception{
		var first=send("GET","/api/v1/auth/session",null,null,null);status(first,200);String cookie=first.headers().firstValue("Set-Cookie").orElseThrow();
		assertThat(cookie).contains("__Host-safecall-session=","Secure","HttpOnly","SameSite=Lax","Path=/").doesNotContain("Domain");
		assertThat(first.body().properties()).hasSize(6);assertThat(first.body().path("profilePrefill").isNull()).isTrue();assertThat(first.text("kind")).isEqualTo("ANONYMOUS");
		Browser b=bootstrap();var second=send("GET","/api/v1/auth/session",null,b,null);assertThat(second.text("csrfToken")).isEqualTo(b.csrf);assertThat(second.headers().allValues("Set-Cookie")).isEmpty();assertThat(count("appUser")).isZero();
	}
	@Test void bootstrapRejectsUnverifiableAndForeignOrigins()throws Exception{
		code(raw("GET","/api/v1/auth/session",null,null,null,null,null,null),403,"ORIGIN_NOT_ALLOWED");code(raw("GET","/api/v1/auth/session",null,null,null,"https://evil.test",null,null),403,"ORIGIN_NOT_ALLOWED");assertThat(count("webSession")).isZero();
	}
	@Test void changesRequireExactOriginAndCsrf()throws Exception{Browser b=bootstrap();code(raw("POST","/api/v1/auth/guest","{}",b,null,null,b.csrf,null),403,"ORIGIN_NOT_ALLOWED");code(raw("POST","/api/v1/auth/guest","{}",b,null,ORIGIN,"wrong",null),403,"CSRF_INVALID");}
	@Test void guestRotatesSessionDoesNotExtendAndCannotReadMemberData()throws Exception{
		Browser b=bootstrap();String old=b.cookie;var r=send("POST","/api/v1/auth/guest",Map.of(),b,null);status(r,200);cookie(b,r);b.csrf=r.text("csrfToken");assertThat(b.cookie).isNotEqualTo(old);
		String expiry=r.text("expiresAt");clock.advance(20);var again=send("POST","/api/v1/auth/guest",Map.of(),b,null);status(again,200);assertThat(again.text("expiresAt")).isEqualTo(expiry);assertThat(again.headers().allValues("Set-Cookie")).isEmpty();code(send("GET","/api/v1/me/profile",null,b,null),403,"LOGIN_REQUIRED");
	}
	@Test void oldJwtAndInstallationContractsAreGone()throws Exception{
		Browser b=bootstrap();status(send("POST","/api/v1/auth/refresh",Map.of(),b,UUID.randomUUID()),404);status(send("POST","/api/v1/auth/kakao",Map.of(),b,null),404);
		status(send("POST","/api/v1/auth/guest",Map.of("installationId",UUID.randomUUID()),b,null),400);
	}
	@Test void oauthAuthenticatesWithoutConsentOrProfileWrites()throws Exception{
		Browser b=bootstrap();String state=start(b,"LOGIN",false);assertThat(count("appUser")).isZero();verifyNoInteractions(kakao);
		var r=callback(b,state);status(r,303);assertThat(r.headers().firstValue("Location")).contains("/onboarding/profile");cookie(b,r);
		var session=send("GET","/api/v1/auth/session",null,b,null);cookie(b,session);assertThat(session.body().path("profilePrefill").path("name").asString()).isEqualTo("홍길동");
		assertThat(session.body().path("profilePrefill").path("phone").asString()).isEqualTo("01012345678");assertThat(b.profilePrefillCookie).isNull();
		assertThat(count("appUser")).isEqualTo(1);assertThat(count("consentEvent")).isZero();
		var user=authRepository.user(userId(b),false);assertThat(user.status()).isEqualTo("ACTIVE");
		assertThat(user.nameCipher()).isNull();assertThat(user.phoneCipher()).isNull();assertThat(user.genderCipher()).isNull();assertThat(user.birthDateCipher()).isNull();
		status(callback(b,state),303);verify(kakao,times(1)).exchange(anyString(),anyString());
	}

	@Test void oauthAcceptsPurposeOnlyAndRejectsLegacyDecisions()throws Exception{
		Browser b=bootstrap();var r=send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","LOGIN"),b,null);status(r,200);
		assertThat(r.text("authorizationUrl")).doesNotContain("scope=");
		status(send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","LOGIN","decisions",decisions(false)),b,null),400);
		status(send("POST","/api/v1/auth/kakao/authorization",Map.of(),b,null),400);
		assertThat(count("oauthAttempt")).isEqualTo(1);
	}

	@Test void oauthCallbackRequiresStartingCookieAndUnexpiredState()throws Exception{Browser b=bootstrap(),other=bootstrap();String state=start(b,"LOGIN",false);assertThat(callback(other,state).headers().firstValue("Location")).contains("/login?reason=oauth_failed");clock.advance(601);assertThat(callback(b,state).headers().firstValue("Location")).contains("/login?reason=oauth_failed");verifyNoInteractions(kakao);}
	@Test void freshLoginCanSaveConsentProfileAndContactsWithPartialRetry()throws Exception{
		Browser b=bootstrap();login(b,"LOGIN",false);
		var initial=send("GET","/api/v1/me/profile",null,b,null);status(initial,200);
		assertThat(initial.body().path("name").isNull()).isTrue();assertThat(initial.body().path("version").asLong()).isEqualTo(1);
		code(send("PATCH","/api/v1/me/profile",profile(1),b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
		code(send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone","01087654321"),b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
		grant(b,false);code(send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID()),422,"PROFILE_REQUIRED");
		UUID profileKey=UUID.randomUUID();status(send("PATCH","/api/v1/me/profile",profile(1),b,profileKey),200);
		code(send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone","01012345678"),b,UUID.randomUUID()),409,"CONTACT_PHONE_CONFLICT");
		status(send("PATCH","/api/v1/me/profile",profile(1),b,profileKey),200);guardian(b,"보호자","01087654321");
		status(composer(b,"?mode=TEST"),200);assertThat(count("consentEvent")).isEqualTo(3);assertThat(count("emergencyContact")).isEqualTo(1);
	}

	@Test void explicitReauthPreservesDataAndMarksSensitiveSession()throws Exception{Browser b=member();String old=b.cookie;login(b,"REAUTH",false);assertThat(b.cookie).isNotEqualTo(old);assertThat(authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).sensitiveVerifiedAt()).isEqualTo(START);assertThat(send("GET","/api/v1/auth/session",null,b,null).body().has("onboardingStep")).isFalse();assertThat(count("consentEvent")).isEqualTo(3);}
	@Test void reauthCannotSwitchAccountsOrAcceptDecisions()throws Exception{Browser b=member();status(send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","REAUTH","decisions",decisions(false)),b,null),400);String state=start(b,"REAUTH",false);when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("99999",null,null,null,null));code(callback(b,state),403,"REAUTH_ACCOUNT_MISMATCH");assertThat(count("appUser")).isEqualTo(1);}
	@Test void logoutIsAtomicRepeatableAndOtherBrowserStaysValid()throws Exception{Browser b=member(),other=member();status(send("POST","/api/v1/auth/logout",Map.of(),b,null),204);status(send("POST","/api/v1/auth/logout",Map.of(),b,null),204);status(send("GET","/api/v1/me/profile",null,other,null),200);status(send("POST","/api/v1/auth/logout",Map.of(),null,null),204);}
	@Test void logoutDatabaseFailureKeepsSession()throws Exception{Browser b=guest();doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic")).when(authRepository).endSession(any(),any(),anyString(),anyString(),any());code(send("POST","/api/v1/auth/logout",Map.of(),b,null),503,"LOGOUT_FAILED");assertThat(authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).status()).isEqualTo("ACTIVE");}
	@Test void onboardingRoutesAndSessionStepAreRemoved()throws Exception{
		Browser b=member();status(send("GET","/api/v1/onboarding",null,b,null),404);
		status(send("POST","/api/v1/onboarding/advance",Map.of("step","PROFILE","expectedVersion",1),b,UUID.randomUUID()),404);
		assertThat(send("GET","/api/v1/auth/session",null,b,null).body().has("onboardingStep")).isFalse();
		complete(b);create(b);
	}

	@Test void profileWithoutPrivacyReturnsEmptyFieldsButStillBlocksWrites()throws Exception{
		Browser b=member();complete(b);jdbc.update("DELETE FROM `consentEvent` WHERE `documentCode`='PRIVACY_PROCESSING'");
		var r=send("GET","/api/v1/me/profile",null,b,null);status(r,200);assertThat(r.body().path("name").isNull()).isTrue();assertThat(r.body().path("profileConfirmedAt").isNull()).isTrue();
		code(send("PATCH","/api/v1/me/profile",profile(2),b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
	}

	@Test void profilePatchNormalizesAndReplaysBeforeVersionCheck()throws Exception{Browser b=member();UUID key=UUID.randomUUID();var body=profile(1);var r=send("PATCH","/api/v1/me/profile",body,b,key);status(r,200);assertThat(r.text("phone")).isEqualTo("01012345678");assertThat(r.body().path("gender").isNull()).isTrue();assertThat(r.body().has("userId")).isFalse();assertThat(r.body().has("profileConfirmedAt")).isTrue();status(send("PATCH","/api/v1/me/profile",body,b,key),200);code(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),409,"VERSION_CONFLICT");}
	@Test void profileRejectsMissingNullableFieldsUnknownFieldsAndInvalidDate()throws Exception{Browser b=member();var body=profile(1);body.remove("gender");status(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),400);body=profile(1);body.put("birthDate","2027-01-01");code(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),422,"INVALID_BIRTH_DATE");body=profile(1);body.put("userId",UUID.randomUUID());status(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),400);}
	@Test void consentsUseCurrentVersionAndEffectiveFields()throws Exception{Browser b=member();var r=send("GET","/api/v1/me/consents",null,b,null);status(r,200);var item=r.body().path("items").get(0);assertThat(item.has("currentVersion")).isTrue();assertThat(item.has("isEffective")).isTrue();assertThat(item.has("isValid")).isFalse();}
	@Test void consentRejectsOldVersionAndRequiresWithdrawalForEffectiveGrant()throws Exception{Browser b=member();code(send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code","AI_CALL","version",99,"action","GRANTED"))),b,UUID.randomUUID()),422,"INVALID_CONSENT");code(send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code","AI_CALL","version",1,"action","DECLINED"))),b,UUID.randomUUID()),409,"WITHDRAWAL_REQUIRED");}
	@Test void sensitiveWithdrawalNeedsRecentExplicitReauthAndReturnsOnlyReceiptCookie()throws Exception{Browser b=member();code(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");login(b,"REAUTH",false);var r=send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID());status(r,202);assertThat(r.text("scope")).isEqualTo("ACCOUNT");assertThat(r.body().has("receiptToken")).isFalse();assertThat(r.headers().firstValue("Set-Cookie").orElseThrow()).contains("__Host-safecall-deletion=","HttpOnly");}
	@Test void reauthExpiresAfterFiveMinutes()throws Exception{Browser b=member();login(b,"REAUTH",false);clock.advance(300);code(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");}
	@Test void aiWithdrawalCleansDataAtomicallyAndRetainsContactAndIdentity()throws Exception{Browser b=member();complete(b);UUID call=create(b);worker.runOne(call);var r=send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID());status(r,202);assertThat(send("GET","/api/v1/calls/"+call,null,b,null).text("endReason")).isEqualTo("CONSENT_WITHDRAWN");cleanup.run();assertThat(count("callSession")).isZero();var user=authRepository.user(userId(b),false);assertThat(user.genderCipher()).isNull();assertThat(user.nameCipher()).isNotNull();assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob`",String.class)).isEqualTo("COMPLETED");}
	@Test void ordinaryReloginPreservesConsentAndProfileWithoutAutoGrant()throws Exception{
		Browser b=member();grant(b,true);complete(b);long events=count("consentEvent");long version=authRepository.user(userId(b),false).version();
		login(b,"LOGIN",false);assertThat(count("consentEvent")).isEqualTo(events);assertThat(count("deletionJob")).isZero();
		assertThat(authRepository.user(userId(b),false).version()).isEqualTo(version);
		status(send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		login(b,"LOGIN",false);code(send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID()),409,"DATA_CLEANUP_PENDING");
		cleanup.run();code(send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
	}

	@Test void contactsEnforceSlotsDuplicatesAndTargetScopedIdempotency()throws Exception{Browser b=member();UUID key=UUID.randomUUID();List<String> ids=new ArrayList<>();for(String phone:List.of("01011112222","01033334444")){var r=send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone",phone),b,UUID.randomUUID());status(r,201);assertThat(r.headers().firstValue("Location")).isPresent();ids.add(r.text("id"));}for(String id:ids)status(send("DELETE","/api/v1/me/emergency-contacts/"+id,Map.of("expectedVersion",1),b,key),204);status(send("DELETE","/api/v1/me/emergency-contacts/"+ids.getFirst(),Map.of("expectedVersion",1),b,key),204);status(send("DELETE","/api/v1/me/emergency-contacts/"+ids.getFirst(),Map.of("expectedVersion",1),b,UUID.randomUUID()),404);assertThat(count("emergencyContact")).isZero();}
	@Test void settingsRequireIdempotencyAndExcludeVibration()throws Exception{Browser b=member();UUID key=UUID.randomUUID();var body=Map.of("incomingAlertMode","SILENT","expectedVersion",1);status(send("PATCH","/api/v1/me/settings",body,b,key),200);status(send("PATCH","/api/v1/me/settings",body,b,key),200);status(send("PATCH","/api/v1/me/settings",body,b,null),400);status(send("PATCH","/api/v1/me/settings",Map.of("incomingAlertMode","VIBRATE","expectedVersion",2),b,UUID.randomUUID()),422);}
	@Test void homeReflectsActiveCallAndCatalogVersion()throws Exception{Browser b=member();complete(b);status(send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone","01011112222"),b,UUID.randomUUID()),201);assertThat(send("GET","/api/v1/home",null,b,null).body().path("isMessageComposeEligible").asBoolean()).isTrue();create(b);assertThat(send("GET","/api/v1/home",null,b,null).body().path("messageBlockReasons").toString()).contains("CALL_ALREADY_OPEN");assertThat(send("GET","/api/v1/call-options",null,b,null).body().path("catalogVersion").asInt()).isEqualTo(3);}
	@Test void callCreationSnapshotsPolicyAndRequiresPageOwnership()throws Exception{Browser b=guest();UUID id=create(b);var r=send("GET","/api/v1/calls/"+id,null,b,null);status(r,200);assertThat(r.text("expiresAt")).isEqualTo(START.plusSeconds(600).toString());assertThat(r.text("leaseExpiresAt")).isEqualTo(START.plusSeconds(30).toString());assertThat(r.body().has("maxResumeAttempts")).isFalse();String page=b.page;b.page=crypto.randomToken();status(send("GET","/api/v1/calls/"+id,null,b,null),404);b.page=page;Browser other=guest();status(send("GET","/api/v1/calls/"+id,null,other,null),404);}
	@Test void sameClientCallIsDeduplicatedButCannotMovePages()throws Exception{Browser b=guest();var body=createBody();var first=send("POST","/api/v1/calls",body,b,UUID.randomUUID());status(first,202);var next=send("POST","/api/v1/calls",body,b,UUID.randomUUID());status(next,202);assertThat(next.text("id")).isEqualTo(first.text("id"));b.page=crypto.randomToken();status(send("POST","/api/v1/calls",body,b,UUID.randomUUID()),404);assertThat(count("connectionGrant")).isEqualTo(1);}
	@Test void duplicateCallKeysConflictOnDifferentBody()throws Exception{Browser b=guest();UUID key=UUID.randomUUID();status(send("POST","/api/v1/calls",createBody(),b,key),202);code(send("POST","/api/v1/calls",createBody(),b,key),409,"IDEMPOTENCY_CONFLICT");}
	@Test void initialGrantIsIssuedOnceAndTokenKeyRemovedOnConnected()throws Exception{Browser b=guest();UUID id=create(b);status(connection(b,id),202);worker.runOne(id);worker.runOne(id);verify(gemini,times(1)).issue(any());var g=connection(b,id);status(g,200);assertThat(g.body().has("sessionResumption")).isFalse();String ref=jdbc.queryForObject("SELECT `keyRef` FROM `connectionGrant`",String.class);assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isTrue();status(event(b,id,"CONNECTED",g.text("grantId")),200);assertThat(jdbc.queryForObject("SELECT `tokenCipher` FROM `connectionGrant`",byte[].class)).isNull();discardQueue.run();assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isFalse();code(connection(b,id),409,"CONNECTION_ALREADY_USED");}
	@Test void invalidTransitionAndEventReplayDoNotCorruptVersion()throws Exception{Browser b=guest();UUID id=create(b);code(event(b,id,"ANSWERED",null),409,"CALL_TRANSITION_INVALID");worker.runOne(id);String grant=connection(b,id).text("grantId");var body=eventBody(b,id,"CONNECTED",grant);UUID key=UUID.randomUUID();status(send("POST","/api/v1/calls/"+id+"/events",body,b,key),200);status(send("POST","/api/v1/calls/"+id+"/events",body,b,UUID.randomUUID()),200);assertThat(send("GET","/api/v1/calls/"+id,null,b,null).body().path("version").asLong()).isEqualTo(3);body.put("type","RINGING_SHOWN");code(send("POST","/api/v1/calls/"+id+"/events",body,b,key),409,"IDEMPOTENCY_CONFLICT");}
	@Test void heartbeatCannotReviveExpiredLease()throws Exception{Browser b=guest();UUID id=create(b);active(b,id);clock.advance(30);code(send("POST","/api/v1/calls/"+id+"/heartbeat",Map.of(),b,null),409,"CALL_TERMINAL");assertThat(send("GET","/api/v1/calls/"+id,null,b,null).text("endReason")).isEqualTo("SESSION_EXPIRED");}
	@Test void heartbeatUpdatesLeaseWithoutExtendingDurationOrVersion()throws Exception{Browser b=guest();UUID id=create(b);active(b,id);var before=send("GET","/api/v1/calls/"+id,null,b,null);clock.advance(5);var beat=send("POST","/api/v1/calls/"+id+"/heartbeat",Map.of(),b,null);status(beat,200);assertThat(beat.text("leaseExpiresAt")).isEqualTo(START.plusSeconds(35).toString());var after=send("GET","/api/v1/calls/"+id,null,b,null);assertThat(after.text("expiresAt")).isEqualTo(before.text("expiresAt"));assertThat(after.body().path("version")).isEqualTo(before.body().path("version"));}
	@Test void callEndIsTerminalAndUsesWebReason()throws Exception{Browser b=guest();UUID id=create(b);worker.runOne(id);status(end(b,id),200);var again=end(b,id);status(again,200);assertThat(again.text("endReason")).isEqualTo("TAB_HIDDEN");assertThat(jdbc.queryForObject("SELECT `status` FROM `connectionGrant`",String.class)).isEqualTo("INVALIDATED");}
	@Test void stalledIssueTimesOutAtTenSecondsAndNeverReissues()throws Exception{Browser b=guest();UUID id=create(b);assertThat(calls.claim(id)).isNotNull();clock.advance(10);worker.runOne(id);code(connection(b,id),503,"CONNECTION_ISSUE_UNKNOWN");verify(gemini,never()).issue(any());}
	@Test void workerDiscardsLateResponseAfterEnd()throws Exception{Browser b=guest();UUID id=create(b);var request=calls.claim(id);status(end(b,id),200);calls.finish(id,request,"auth_tokens/late",null);assertThat(jdbc.queryForObject("SELECT `tokenCipher` FROM `connectionGrant`",byte[].class)).isNull();assertThat(connection(b,id).status()).isEqualTo(409);}
	@Test void concurrentSameCreateIsSingleCall()throws Exception{Browser b=guest();var body=createBody();UUID key=UUID.randomUUID();try(var executor=Executors.newFixedThreadPool(2)){var a=executor.submit(()->send("POST","/api/v1/calls",body,b,key));var c=executor.submit(()->send("POST","/api/v1/calls",body,b,key));status(a.get(),202);status(c.get(),202);}assertThat(count("callSession")).isEqualTo(1);}
	@Test void concurrentContactCreatesEnforceTwoSlots()throws Exception{Browser b=member();try(var executor=Executors.newFixedThreadPool(3)){List<Future<Result>> results=new ArrayList<>();for(String phone:List.of("01011112222","01033334444","01055556666"))results.add(executor.submit(()->send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone",phone),b,UUID.randomUUID())));List<Integer> statuses=new ArrayList<>();for(var f:results)statuses.add(f.get().status());assertThat(statuses).containsExactlyInAnyOrder(201,201,409);}assertThat(count("emergencyContact")).isEqualTo(2);}
	@Test void unknownJsonAndDuplicateKeysAreRejectedWithoutLeakingValues()throws Exception{Browser b=bootstrap();var r=raw("POST","/api/v1/auth/guest","{\"secret\":\"sensitive\",\"secret\":\"sensitive\"}",b,null,ORIGIN,b.csrf,null);status(r,400);assertThat(r.body().toString()).doesNotContain("sensitive");assertThat(r.text("timestamp")).endsWith("Z");}
	@Test void openApiExposesOnlyImplementedWebOperations()throws Exception{var r=send("GET","/v3/api-docs",null,null,null);status(r,200);var paths=r.body().path("paths");assertThat(r.body().path("servers").path(0).path("url").asString()).isEqualTo("/");assertThat(paths.has("/api/v1/auth/refresh")).isFalse();assertThat(paths.has("/api/v1/documents/{code}")).isFalse();assertThat(paths.has("/api/v1/onboarding")).isFalse();assertThat(paths.has("/api/v1/onboarding/advance")).isFalse();assertThat(r.body().path("components").path("schemas").path("AuthorizationRequest").path("properties").has("decisions")).isFalse();assertThat(paths.has("/api/v1/calls/{callId}/connection-renewals")).isFalse();assertThat(paths.path("/api/v1/me/profile").has("patch")).isTrue();assertThat(r.body().path("components").path("securitySchemes").path("webSession").path("in").asString()).isEqualTo("cookie");}

	@Test void deletionViewIncludesAllSevenFieldsAndReplaysCurrentStatus()throws Exception{
		Browser b=member();UUID key=UUID.randomUUID();var result=send("POST","/api/v1/me/consents/LOCATION_PROCESSING/withdrawal",Map.of(),b,key);status(result,202);
		assertThat(result.body().properties()).hasSize(7);assertThat(result.body().has("requestedAt")).isTrue();assertThat(result.body().has("completedAt")).isTrue();
		cleanup.run();result=send("POST","/api/v1/me/consents/LOCATION_PROCESSING/withdrawal",Map.of(),b,key);status(result,202);assertThat(result.text("status")).isEqualTo("COMPLETED");
	}
	@Test void contactPatchNormalizesAndUsesCurrentResponseOnReplay()throws Exception{
		Browser b=member();var created=send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone","01011112222"),b,UUID.randomUUID());status(created,201);
		UUID key=UUID.randomUUID();String path="/api/v1/me/emergency-contacts/"+created.text("id");var body=Map.of("name","가족","relationship","친구","phone","+82 10-1111-2222","expectedVersion",1);
		var result=send("PATCH",path,body,b,key);status(result,200);assertThat(result.text("phone")).isEqualTo("01011112222");status(send("PATCH",path,body,b,key),200);code(send("PATCH",path,body,b,UUID.randomUUID()),409,"VERSION_CONFLICT");
	}
	@Test void aiConsentIsRequiredForNewDemographicsEvenWithoutPriorWithdrawal()throws Exception{
		Browser b=member();jdbc.update("DELETE FROM `consentEvent` WHERE `documentCode`='AI_CALL'");var body=profile(1);body.put("gender","FEMALE");code(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
	}
	@Test void declinedPrivacyAllowsLoginButBlocksPersonalDataAndCalls()throws Exception{
		Browser b=bootstrap();login(b,"LOGIN",false);var choices=new ArrayList<>(decisions(false));choices.set(0,Map.of("code","PRIVACY_PROCESSING","version",1,"action","DECLINED"));
		status(send("POST","/api/v1/me/consents",Map.of("decisions",choices),b,UUID.randomUUID()),200);
		code(send("PATCH","/api/v1/me/profile",profile(1),b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
		code(send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID()),403,"CONSENT_REQUIRED");
	}
	@Test void reauthAuthorizationRequiresPromptLoginAndNoProfileScopes()throws Exception{
		Browser b=member();var r=send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","REAUTH"),b,null);status(r,200);assertThat(r.text("authorizationUrl")).contains("prompt=login").doesNotContain("scope=");
	}
	@Test void browserSuppliedEventTimestampsDoNotControlServerStateTime()throws Exception{
		Browser b=guest();UUID id=create(b);worker.runOne(id);String grant=connection(b,id).text("grantId");var body=eventBody(b,id,"CONNECTED",grant);body.put("occurredAt","2020-01-01T00:00:00Z");status(send("POST","/api/v1/calls/"+id+"/events",body,b,UUID.randomUUID()),200);status(event(b,id,"RINGING_SHOWN",null),200);
		assertThat(send("GET","/api/v1/calls/"+id,null,b,null).text("ringingAt")).isEqualTo(START.toString());
	}
	@Test void callIsBoundedByRemainingGuestSessionLifetime()throws Exception{
		Browser b=guest();jdbc.update("UPDATE `webSession` SET `expiresAt`=? WHERE `id`=?",time(START.plusSeconds(15)),bin(sessionId(b)));UUID id=create(b);var r=send("GET","/api/v1/calls/"+id,null,b,null);assertThat(r.text("expiresAt")).isEqualTo(START.plusSeconds(15).toString());assertThat(r.text("leaseExpiresAt")).isEqualTo(r.text("expiresAt"));
	}
	@Test void maximumDurationWinsOverARecentHeartbeat()throws Exception{
		Browser b=guest();UUID id=create(b);active(b,id);jdbc.update("UPDATE `callSession` SET `lastHeartbeatAt`=?,`leaseExpiresAt`=? WHERE `id`=?",time(START.plusSeconds(599)),time(START.plusSeconds(600)),bin(id));clock.advance(600);code(send("POST","/api/v1/calls/"+id+"/heartbeat",Map.of(),b,null),409,"CALL_TERMINAL");assertThat(send("GET","/api/v1/calls/"+id,null,b,null).text("endReason")).isEqualTo("DURATION_LIMIT");
	}
	@Test void callLimitsReturnRetryAfterAndDoNotCountIdempotentReplay()throws Exception{
		Browser b=guest();for(int i=0;i<5;i++){var body=createBody();UUID key=UUID.randomUUID();var r=send("POST","/api/v1/calls",body,b,key);status(r,202);status(send("POST","/api/v1/calls",body,b,key),202);end(b,UUID.fromString(r.text("id")));}var limited=send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID());code(limited,429,"RATE_LIMITED");assertThat(limited.headers().firstValue("Retry-After")).contains("60");
	}
	@Test void cleanupRollbackPreservesAllTargetDataOnDbFailure()throws Exception{
		Browser b=member();complete(b);UUID id=create(b);status(send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		jdbc.execute("CREATE TRIGGER `synthetic_cleanup_failure` BEFORE UPDATE ON `deletionJob` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
		try{cleanup.run();assertThat(count("callSession")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob`",String.class)).isEqualTo("PENDING");}
		finally{jdbc.execute("DROP TRIGGER `synthetic_cleanup_failure`");}
		cleanup.run();assertThat(count("callSession")).isZero();
	}
	@Test void logoutEndsOnlyCurrentBrowserCallAndInvalidatesGrant()throws Exception{
		Browser b=member();complete(b);UUID id=create(b);worker.runOne(id);status(send("POST","/api/v1/auth/logout",Map.of(),b,null),204);assertThat(jdbc.queryForObject("SELECT `endReason` FROM `callSession` WHERE `id`=?",String.class,bin(id))).isEqualTo("LOGOUT");assertThat(jdbc.queryForObject("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=?",String.class,bin(id))).isNull();
	}
	@Test void accountCleanupPreservesReceiptWindowThenAtomicallyDeletesLocalData()throws Exception{
		Browser b=member();complete(b);UUID user=userId(b);String keyRef=authRepository.user(user,false).keyRef();
		status(send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		login(b,"REAUTH",false);var receipt=send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID());status(receipt,202);
		accountsCleanup.run();assertThat(count("appUser")).isEqualTo(1);clock.advance(60);accountsCleanup.run();
		assertThat(count("appUser")).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `webSession` WHERE `userId`=?",Long.class,bin(user))).isZero();assertThat(count("consentEvent")).isZero();
		var job=jdbc.queryForMap("SELECT * FROM `deletionJob` WHERE `scope`='ACCOUNT'");assertThat(job.get("status")).isEqualTo("LOCAL_DELETED");assertThat(job.get("userId")).isNull();assertThat(job.get("accountSubjectHash")).isNotNull();
		discardQueue.run();assertThatThrownBy(()->userKeys.read(keyRef)).isInstanceOf(RuntimeException.class);
		byte[] payload=crypto.open(userKeys.read((String)job.get("cleanupKeyRef")),"ACCOUNT_CLEANUP:"+receipt.text("id"),(byte[])job.get("cleanupCipher"));
		assertThat(mapper.readTree(payload).path("subject").asString()).isEqualTo("12345");
		assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob` WHERE `scope`='AI_DATA'",String.class)).isEqualTo("COMPLETED");
	}
	@Test void accountCleanupRollbackKeepsDataAndPersonalKey()throws Exception{
		Browser b=member();UUID user=userId(b);String ref=authRepository.user(user,false).keyRef();byte[] original=userKeys.read(ref);
		login(b,"REAUTH",false);status(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),202);clock.advance(60);
		jdbc.execute("CREATE TRIGGER `synthetic_account_failure` BEFORE UPDATE ON `deletionJob` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
		try{accountsCleanup.run();assertThat(count("appUser")).isEqualTo(1);assertThat(userKeys.read(ref)).isEqualTo(original);assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob`",String.class)).isEqualTo("PENDING");}
		finally{jdbc.execute("DROP TRIGGER `synthetic_account_failure`");}
		accountsCleanup.run();assertThat(count("appUser")).isZero();
	}
	@Test void accountWithoutCompletedProfileIsNotDeletedAfter24Hours()throws Exception{
		Browser b=bootstrap();login(b,"LOGIN",false);clock.advance(86401);accountsCleanup.run();
		assertThat(count("deletionJob")).isZero();assertThat(count("appUser")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT `status` FROM `appUser`",String.class)).isEqualTo("ACTIVE");
	}

	@Test void retentionRemovesExpiredGuestCallsAndKeysButKeepsActiveMember()throws Exception{
		Browser member=member();complete(member);Browser guest=guest();UUID call=create(guest);worker.runOne(call);
		String ref=jdbc.queryForObject("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=?",String.class,bin(call));
		clock.advance(90000);retention.run();discardQueue.run();assertThat(count("callSession")).isZero();assertThat(count("appUser")).isEqualTo(1);assertThat(count("webSession")).isEqualTo(1);assertThatThrownBy(()->userKeys.read(ref)).isInstanceOf(RuntimeException.class);
	}
	@Test void retentionKeepsMemberCallFor30DaysThenRemovesIt()throws Exception{
		Browser b=member();complete(b);UUID call=create(b);end(b,call);clock.advance(2591999);retention.run();assertThat(count("callSession")).isEqualTo(1);
		clock.advance(1);retention.run();assertThat(count("callSession")).isZero();assertThat(count("webSession")).isEqualTo(1);
		clock.advance(1209600);retention.run();assertThat(count("webSession")).isZero();assertThat(count("appUser")).isEqualTo(1);
	}
	@Test void scheduledReaperFindsExpiredCallsInMySql()throws Exception{
		Browser b=guest();UUID call=create(b);active(b,call);clock.advance(30);worker.tick();
		assertThat(jdbc.queryForObject("SELECT `state` FROM `callSession` WHERE `id`=?",String.class,bin(call))).isEqualTo("ENDED");
	}
	@Test void finalSchemaHasExpectedTablesColumnsAndConstraints(){
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isEqualTo(18);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()",Integer.class)).isEqualTo(177);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE()",Integer.class)).isEqualTo(152);
	}
	@Test void aiWithdrawalMasksDemographicsBeforeWorkerAndDoesNotBlockLocationConsent()throws Exception{
		Browser b=member();status(send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		var profile=send("GET","/api/v1/me/profile",null,b,null);status(profile,200);assertThat(profile.body().path("gender").isNull()).isTrue();assertThat(profile.text("genderSource")).isEqualTo("UNKNOWN");
		status(send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code","LOCATION_PROCESSING","version",1,"action","GRANTED"))),b,UUID.randomUUID()),200);
	}
	@Test void cspNonceChangesAndKnownKakaoInAppCannotStartReauth()throws Exception{
		Browser b=member();String first=send("GET","/api/v1/auth/session",null,b,null).headers().firstValue("Content-Security-Policy").orElseThrow();
		assertThat(first).contains("'nonce-");assertThat(send("GET","/api/v1/auth/session",null,b,null).headers().firstValue("Content-Security-Policy").orElseThrow()).isNotEqualTo(first);
		var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/kakao/authorization")).header("Content-Type","application/json").header("Origin",ORIGIN).header("X-CSRF-Token",b.csrf).header("Cookie","__Host-safecall-session="+b.cookie).header("User-Agent","KAKAOTALK synthetic").POST(HttpRequest.BodyPublishers.ofString("{\"purpose\":\"REAUTH\"}")).build();
		assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
	}
	@Test void oauthKeyReadFailureRollsBackAccountAndDiscardsNewKey()throws Exception{
		Browser b=bootstrap();String state=start(b,"LOGIN",false);
		doThrow(new IllegalStateException("synthetic key read failure")).when(userKeys).read(anyString());
		var result=callback(b,state);status(result,303);
		assertThat(result.headers().firstValue("Location")).contains("/login?reason=oauth_failed");
		assertThat(count("appUser")).isZero();
		assertThat(jdbc.queryForObject("SELECT `status` FROM `oauthAttempt`",String.class)).isEqualTo("FAILED");
		verify(userKeys).discard(anyString());
	}
	@Test void anonymousAndForeignCookiesCannotUseCachedCatalog()throws Exception{
		Browser b=bootstrap();code(send("GET","/api/v1/call-options",null,b,null),401,"AUTHENTICATION_REQUIRED");
	}

	Result guardian(Browser b, String name, String phone) throws Exception {
		var result=send("POST","/api/v1/me/emergency-contacts",Map.of("name",name,"relationship","가족","phone",phone),b,UUID.randomUUID());
		status(result,201); return result;
	}
	Browser composerMember() throws Exception {
		Browser b=member(); complete(b); guardian(b,"보호자","01087654321"); return b;
	}
	Result composer(Browser b, String query) throws Exception { return send("GET","/api/v1/message-composer"+query,null,b,null); }

	@Test void composerReturnsOrderedContactsMaskedIdentityAndFiveMinuteMaterials() throws Exception {
		Browser b=composerMember(); guardian(b,"두번째","01011112222");
		var result=composer(b,""); status(result,200);
		assertThat(result.body().properties()).hasSize(10);
		assertThat(result.text("mode")).isEqualTo("SAFETY");
		assertThat(result.body().path("recipients")).isEqualTo(send("GET","/api/v1/me/emergency-contacts",null,b,null).body().path("items"));
		assertThat(result.body().path("recipients").get(0).path("slot").asInt()).isEqualTo(1);
		assertThat(result.body().path("recipients").get(1).path("slot").asInt()).isEqualTo(2);
		assertThat(result.body().path("identity").properties()).hasSize(2);
		assertThat(result.body().path("identity").path("maskedPhone").asString()).isEqualTo("010-xxxx-5678");
		assertThat(result.text("baseBody")).isEqualTo("홍길동(010-xxxx-5678)의 SafeCall 안심 메시지입니다.");
		assertThat(result.body().toString()).doesNotContain("01012345678","birthDate","gender","keyRef");
		assertThat(result.body().path("templateVersion").asInt()).isEqualTo(3);
		assertThat(result.body().path("mapTemplate").isNull()).isTrue();
		assertThat(result.body().path("isLocationConsentGranted").asBoolean()).isFalse();
		assertThat(result.text("notice")).isEqualTo("이 화면에서는 실제 문자가 발송되지 않습니다.");
		assertThat(result.text("preparedAt")).isEqualTo(START.toString());
		assertThat(result.text("expiresAt")).isEqualTo(START.plusSeconds(300).toString());
		assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(result.headers().firstValue("ETag")).isEmpty();
	}
	@Test void composerRevalidatesEveryReadWithoutCreatingRecordsOrUsingConditionalCache() throws Exception {
		Browser b=composerMember(); long events=count("operationEvent"), replays=count("apiIdempotency");
		status(composer(b,""),200); clock.advance(1);
		var p=profile(send("GET","/api/v1/me/profile",null,b,null).body().path("version").asLong());
		p.put("name","변경이름"); p.put("phone","01022223333");
		status(send("PATCH","/api/v1/me/profile",p,b,UUID.randomUUID()),200);
		var result=composer(b,""); status(result,200);
		assertThat(result.text("baseBody")).startsWith("변경이름(010-xxxx-3333)");
		assertThat(result.text("preparedAt")).isEqualTo(START.plusSeconds(1).toString());
		assertThat(count("operationEvent")).isEqualTo(events);
		assertThat(count("apiIdempotency")).isEqualTo(replays+1);
		assertThat(count("callSession")).isZero();
		var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/message-composer"))
			.header("Cookie","__Host-safecall-session="+b.cookie).header("If-None-Match","*").GET().build();
		assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
		jdbc.update("DELETE FROM `emergencyContact` WHERE `userId`=?",bin(userId(b)));
		code(composer(b,""),409,"CONTACT_REQUIRED");
	}
	@Test void composerUsesProfileAndConsentInsteadOfScreenSteps()throws Exception{
		Browser b=member();guardian(b,"보호자","01087654321");code(composer(b,"?mode=TEST"),409,"PROFILE_REQUIRED");
		status(send("PATCH","/api/v1/me/profile",profile(1),b,UUID.randomUUID()),200);
		var result=composer(b,"?mode=TEST");status(result,200);
		assertThat(result.text("baseBody")).isEqualTo("[테스트] 홍길동(010-xxxx-5678)의 SafeCall 안심 메시지입니다.");
		status(composer(b,"?mode=SAFETY"),200);
	}

	@Test void composerRejectsMissingGuestAnonymousExpiredAndLoggedOutSessions() throws Exception {
		code(composer(null,""),401,"SESSION_EXPIRED");
		code(composer(bootstrap(),""),403,"LOGIN_REQUIRED"); code(composer(guest(),""),403,"LOGIN_REQUIRED");
		Browser b=composerMember();
		jdbc.update("UPDATE `webSession` SET `expiresAt`=? WHERE `id`=?",time(START.plusSeconds(30)),bin(sessionId(b)));
		assertThat(composer(b,"").text("expiresAt")).isEqualTo(START.plusSeconds(30).toString());
		clock.advance(30); code(composer(b,""),401,"SESSION_EXPIRED");
		Browser next=member(); status(send("POST","/api/v1/auth/logout",Map.of(),next,null),204);
		code(composer(next,""),401,"SESSION_EXPIRED");
	}
	@Test void composerRejectsUnconfirmedOrMissingProfileWith409WithoutChangingProfilePatchErrors() throws Exception {
		Browser b=composerMember();
		jdbc.update("UPDATE `appUser` SET `profileConfirmedAt`=NULL WHERE `id`=?",bin(userId(b)));
		code(composer(b,""),409,"PROFILE_REQUIRED");
		var p=profile(2); p.put("name",""); code(send("PATCH","/api/v1/me/profile",p,b,UUID.randomUUID()),422,"PROFILE_REQUIRED");
		jdbc.update("UPDATE `appUser` SET `profileConfirmedAt`=?,`nameCipher`=NULL WHERE `id`=?",time(START),bin(userId(b)));
		code(composer(b,""),409,"PROFILE_REQUIRED");
	}
	@Test void composerRequiresLatestFixedVersionPrivacyConsent() throws Exception {
		Browser b=composerMember();
		jdbc.update("INSERT INTO `consentEvent` (`id`,`userId`,`documentCode`,`documentVersion`,`action`,`recordedAt`) VALUES (?,?, 'PRIVACY_PROCESSING',1,'WITHDRAWN',?)",bin(UUID.randomUUID()),bin(userId(b)),time(START.plusNanos(1000)));
		code(composer(b,""),403,"CONSENT_REQUIRED");
		jdbc.update("INSERT INTO `consentEvent` (`id`,`userId`,`documentCode`,`documentVersion`,`action`,`recordedAt`) VALUES (?,?, 'PRIVACY_PROCESSING',1,'GRANTED',?)",bin(UUID.randomUUID()),bin(userId(b)),time(START.plusNanos(2000)));
		status(composer(b,""),200);
	}
	@Test void composerLocationConsentIsOptionalAndAiConsentIsIndependentAfterCleanup() throws Exception {
		Browser b=composerMember();
		status(send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code","LOCATION_PROCESSING","version",1,"action","GRANTED"))),b,UUID.randomUUID()),200);
		assertThat(composer(b,"").body().path("isLocationConsentGranted").asBoolean()).isTrue();
		status(send("POST","/api/v1/me/consents/LOCATION_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		code(composer(b,""),409,"DATA_CLEANUP_PENDING"); cleanup.run();
		var withoutLocation=composer(b,""); status(withoutLocation,200); assertThat(withoutLocation.body().path("isLocationConsentGranted").asBoolean()).isFalse();
		status(send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		code(composer(b,""),409,"DATA_CLEANUP_PENDING"); cleanup.run(); status(composer(b,""),200);
	}
	@Test void composerRefusesAccountDeletionBeforeDecryptingProfile() throws Exception {
		Browser b=composerMember(); login(b,"REAUTH",false);
		status(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),202);
		clearInvocations(userKeys); code(composer(b,""),409,"DATA_CLEANUP_PENDING"); verify(userKeys,never()).read(anyString());
	}
	@Test void composerBlocksOpenCallOnlyInCurrentSessionAndAllowsEndedCall() throws Exception {
		Browser b=composerMember(); Browser other=member();
		UUID call=create(b); code(composer(b,""),409,"CALL_ALREADY_OPEN"); code(composer(b,"?mode=TEST"),409,"CALL_ALREADY_OPEN");
		status(composer(other,""),200); status(end(b,call),200); status(composer(b,""),200);
	}
	@Test void composerRejectsUnknownDuplicateParametersAndNeverEchoesPrivateInputs() throws Exception {
		Browser b=composerMember();
		for (String query:List.of("?mode=","?mode=OTHER","?mode=safety","?mode=SAFETY&mode=TEST","?latitude=37.5","?userId=secret")) {
			var result=composer(b,query); code(result,400,"INVALID_REQUEST");
			assertThat(result.body().properties()).hasSize(6);
			assertThat(result.text("path")).isEqualTo("/api/v1/message-composer");
			assertThat(result.body().toString()).doesNotContain("37.5","secret");
			assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
		}
		code(send("GET","/api/v1/message-composer",Map.of("latitude",37.5),b,null),400,"INVALID_REQUEST");
		code(raw("GET","/api/v1/message-composer",null,b,null,"https://evil.test",null,null),403,"ORIGIN_NOT_ALLOWED");
	}
	@Test void composerDoesNotExposeLocationOrSmsRoutesAndOpenApiUsesCookieOnly() throws Exception {
		Browser b=composerMember();
		for (String path:List.of("/api/v1/message-composer/location-link","/api/v1/send-sms","/api/v1/sms/send"))
			code(send("POST",path,Map.of(),b,null),404,"RESOURCE_NOT_FOUND");
		var spec=send("GET","/v3/api-docs",null,null,null).body();
		var operation=spec.path("paths").path("/api/v1/message-composer").path("get");
		assertThat(operation.path("operationId").asString()).isEqualTo("M01");
		assertThat(operation.path("security").toString()).contains("webSession");
		assertThat(operation.path("parameters").toString()).contains("mode","SAFETY","TEST").doesNotContain("X-CSRF-Token","Idempotency-Key");
	}
	@Test void composerReadsCommittedProfileAndRecipientTogetherAfterWaitingForAccountLock() throws Exception {
		Browser b=composerMember(); UUID user=userId(b);
		UUID contact=UUID.fromString(send("GET","/api/v1/me/emergency-contacts",null,b,null).body().path("items").get(0).path("id").asString());
		byte[] key=userKeys.read(authRepository.user(user,false).keyRef());
		var writerReady=new CountDownLatch(1); var readerReady=new CountDownLatch(1); var commitWriter=new CountDownLatch(1);
		doAnswer(invocation -> { readerReady.countDown(); return invocation.callRealMethod(); }).when(authRepository).user(user,true);
		try (var executor=Executors.newFixedThreadPool(2)) {
			var writer=executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
				jdbc.update("UPDATE `appUser` SET `nameCipher`=?,`version`=`version`+1 WHERE `id`=?",
					crypto.seal(key,user+":name","최신이름".getBytes(java.nio.charset.StandardCharsets.UTF_8)),bin(user));
				jdbc.update("UPDATE `emergencyContact` SET `phoneCipher`=?,`phoneHash`=?,`version`=`version`+1 WHERE `id`=?",
					crypto.seal(key,contact+":phone","01055556666".getBytes(java.nio.charset.StandardCharsets.UTF_8)),crypto.hash("PHONE_MATCH:"+user,"01055556666"),bin(contact));
				writerReady.countDown();
				try { if (!commitWriter.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out."); }
				catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
			}));
			try {
				assertThat(writerReady.await(5,TimeUnit.SECONDS)).isTrue();
				var reader=executor.submit(() -> composer(b,""));
				assertThat(readerReady.await(5,TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				commitWriter.countDown(); writer.get(5,TimeUnit.SECONDS);
				var result=reader.get(5,TimeUnit.SECONDS); status(result,200);
				assertThat(result.body().path("identity").path("name").asString()).isEqualTo("최신이름");
				assertThat(result.body().path("recipients").get(0).path("phone").asString()).isEqualTo("01055556666");
				assertThat(result.body().path("recipients").get(0).path("version").asInt()).isEqualTo(2);
			} finally { commitWriter.countDown(); }
		}
	}
	@Test void composerKeepsRecipientsWithinAccountAndReflectsContactEdits() throws Exception {
		Browser b=composerMember();
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("67890","다른회원","MALE",LocalDate.of(2000,1,1),"01033334444"));
		Browser other=member(); complete(other); guardian(other,"다른보호자","01099998888");
		var before=composer(b,""); status(before,200);
		assertThat(before.body().path("recipients").size()).isEqualTo(1);
		assertThat(before.body().toString()).doesNotContain("다른보호자","01099998888");
		var contact=before.body().path("recipients").get(0);
		var edited=send("PATCH","/api/v1/me/emergency-contacts/"+contact.path("id").asString(),
			Map.of("name","수정보호자","relationship","친구","phone","01055556666","expectedVersion",contact.path("version").asLong()),b,UUID.randomUUID());
		status(edited,200);
		assertThat(composer(b,"").body().path("recipients").get(0)).isEqualTo(edited.body());
		assertThat(composer(other,"").body().toString()).contains("다른보호자").doesNotContain("수정보호자");
	}
	@Test void composerReturnsReviewedMapMetadataWithoutCoordinatesOrReviewEvidence() throws Exception {
		var template=new com.safecall.service.message.service.MessagePolicy(3,
			"https://map.naver.com/synthetic?lat={latitude}&lon={longitude}","LAT_LON","map.naver.com","synthetic-browser","test-only").mapTemplate();
		doReturn(template).when(messagePolicy).mapTemplate();
		var result=composer(composerMember(),""); status(result,200);
		var map=result.body().path("mapTemplate"); assertThat(map.properties()).hasSize(5);
		assertThat(map.path("urlTemplate").asString()).contains("{latitude}","{longitude}");
		assertThat(map.path("coordinateSystem").asString()).isEqualTo("WGS84");
		assertThat(map.path("maxAgeSeconds").asInt()).isEqualTo(30);
		assertThat(map.path("maxAccuracyMeters").asInt()).isEqualTo(100);
		assertThat(result.body().toString()).doesNotContain("validationRef","test-only","reviewedBrowsers","synthetic-browser");
		assertThat(result.text("baseBody")).doesNotContain("https://");
	}
	Result history(Browser b,String query)throws Exception{return send("GET","/api/v1/me/usage-history"+query,null,b,null);}
	Browser returningMember()throws Exception{Browser b=member();complete(b);return b;}
	Result deletion(Browser b,String scope,UUID key)throws Exception{return send("POST","/api/v1/me/data-deletions",Map.of("scope",scope,"isConfirmed",true),b,key);}
	String receipt(Result r){String value=r.headers().firstValue("Set-Cookie").orElseThrow();return value.substring(value.indexOf('=')+1,value.indexOf(';'));}
	Result deletionStatus(String id,Browser b,String receipt)throws Exception{
		var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/data-deletions/"+id)).header("Origin",ORIGIN);
		List<String> cookies=new ArrayList<>();if(b!=null&&b.cookie!=null)cookies.add("__Host-safecall-session="+b.cookie);if(receipt!=null)cookies.add("__Host-safecall-deletion="+receipt);
		if(!cookies.isEmpty())request.header("Cookie",String.join("; ",cookies));
		var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
		return new Result(response.statusCode(),mapper.readTree(response.body()),response.headers());
	}
	@Test void usageHistoryUsesStableKeysetAcrossMemberSessionsWithMinimalFields()throws Exception{
		Browser b=member();complete(b);Browser second=returningMember();
		List<String> ids=new ArrayList<>();for(int i=0;i<5;i++){Browser owner=i%2==0?b:second;UUID id=create(owner);status(end(owner,id),200);ids.add(id.toString());}
		ids.sort(Comparator.reverseOrder());
		var first=history(b,"?limit=2");status(first,200);assertThat(first.body().properties()).hasSize(2);
		assertThat(first.body().path("items").size()).isEqualTo(2);assertThat(first.body().path("items").get(0).properties()).hasSize(10);
		assertThat(first.body().path("items").get(0).path("id").asString()).isEqualTo(ids.getFirst());
		assertThat(first.text("nextCursor")).matches("[A-Za-z0-9_-]{16,512}");
		var next=history(second,"?limit=2&cursor="+first.text("nextCursor"));status(next,200);
		var last=history(b,"?limit=2&cursor="+next.text("nextCursor"));status(last,200);
		List<String> actual=new ArrayList<>();for(var page:List.of(first,next,last))for(var item:page.body().path("items"))actual.add(item.path("id").asString());
		assertThat(actual).containsExactlyElementsOf(ids);assertThat(last.body().path("nextCursor").isNull()).isTrue();
		assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");assertThat(first.headers().firstValue("ETag")).isEmpty();
		assertThat(first.body().toString()).doesNotContain("pageKey","leaseExpiresAt","token","홍길동","01012345678");
	}
	@Test void usageHistoryExcludesOpenGuestAndOtherAccountCallsButIncludesFailures()throws Exception{
		Browser b=member();complete(b);UUID ended=create(b);status(end(b,ended),200);UUID failed=create(b);status(end(b,failed),200);
		jdbc.update("UPDATE `callSession` SET `state`='FAILED',`endReason`='CONNECTION_FAILED' WHERE `id`=?",bin(failed));create(b);
		Browser g=guest();UUID guestCall=create(g);status(end(g,guestCall),200);
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("99999","다른회원",null,null,"01022223333"));
		Browser other=member();complete(other);UUID otherCall=create(other);status(end(other,otherCall),200);
		var result=history(b,"");status(result,200);assertThat(result.body().path("items").size()).isEqualTo(2);
		assertThat(result.body().toString()).contains("ENDED","FAILED").doesNotContain(guestCall.toString(),otherCall.toString());
		code(history(g,""),403,"LOGIN_REQUIRED");code(history(null,""),401,"SESSION_EXPIRED");
	}
	@Test void usageHistoryRejectsInvalidLimitCursorAndCrossAccountCursor()throws Exception{
		Browser b=member();complete(b);for(int i=0;i<2;i++){UUID id=create(b);status(end(b,id),200);}
		String cursor=history(b,"?limit=1").text("nextCursor");
		for(String query:List.of("?limit=0","?limit=101","?limit=-1","?limit=1.5","?limit=","?limit=2&limit=3","?userId=private"))status(history(b,query),400);
		for(String query:List.of("?cursor=","?cursor=private","?cursor="+cursor+"=","?cursor="+cursor+"&cursor="+cursor))code(history(b,query),400,"INVALID_CURSOR");
		String tampered=(cursor.startsWith("A")?"B":"A")+cursor.substring(1);code(history(b,"?cursor="+tampered),400,"INVALID_CURSOR");
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("99999",null,null,null,null));
		code(history(member(),"?cursor="+cursor),400,"INVALID_CURSOR");
	}
	@Test void usageHistorySurvivesDeletedCursorAnchorAndAcceptsMaxLimit()throws Exception{
		Browser b=member();complete(b);UUID first=create(b);status(end(b,first),200);clock.advance(1);UUID second=create(b);status(end(b,second),200);
		var page=history(b,"?limit=1");jdbc.update("DELETE FROM `callSession` WHERE `id`=?",bin(second));
		var next=history(b,"?cursor="+page.text("nextCursor"));status(next,200);assertThat(next.body().path("items").get(0).path("id").asString()).isEqualTo(first.toString());
		status(history(b,"?limit=100"),200);
	}
	@Test void usageHistoryDefaultPageHasTwentyItemsAndNoConditionalCache()throws Exception{
		Browser b=member();complete(b);UUID seed=create(b);status(end(b,seed),200);
		for(int i=0;i<20;i++)jdbc.update("""
			INSERT INTO `callSession` (`id`,`sessionId`,`pageKeyHash`,`clientCallId`,`startMode`,`releaseId`,`scenarioCode`,`counterpartCode`,
			`state`,`endReason`,`isDemographicApplied`,`isGenderAddressApplied`,`createdAt`,`endedAt`,`lastHeartbeatAt`,`leaseExpiresAt`,`expiresAt`)
			SELECT ?,`sessionId`,`pageKeyHash`,?,`startMode`,`releaseId`,`scenarioCode`,`counterpartCode`,`state`,`endReason`,
			`isDemographicApplied`,`isGenderAddressApplied`,`createdAt`,`endedAt`,`lastHeartbeatAt`,`leaseExpiresAt`,`expiresAt` FROM `callSession` WHERE `id`=?
			""",bin(UUID.randomUUID()),bin(UUID.randomUUID()),bin(seed));
		var first=history(b,"");status(first,200);assertThat(first.body().path("items").size()).isEqualTo(20);
		var second=history(b,"?cursor="+first.text("nextCursor"));status(second,200);assertThat(second.body().path("items").size()).isEqualTo(1);
		var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/usage-history")).header("Cookie","__Host-safecall-session="+b.cookie).header("If-None-Match","*").GET().build();
		assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
	}
	@Test void concurrentDeletionRequestsShareJobAndReceipt()throws Exception{
		Browser b=member();
		try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
			var first=executor.submit(()->deletion(b,"USAGE_HISTORY",UUID.randomUUID()));var second=executor.submit(()->deletion(b,"USAGE_HISTORY",UUID.randomUUID()));
			var a=first.get(10,TimeUnit.SECONDS);var c=second.get(10,TimeUnit.SECONDS);status(a,202);status(c,202);
			assertThat(a.text("id")).isEqualTo(c.text("id"));assertThat(receipt(a)).isEqualTo(receipt(c));assertThat(count("deletionJob")).isEqualTo(1);
		}
	}
	@Test void accountRequestReplaySurvivesReauthWindowButNewKeyRequiresReauth()throws Exception{
		Browser b=member();login(b,"REAUTH",false);clock.advance(250);UUID key=UUID.randomUUID();var first=deletion(b,"ACCOUNT",key);status(first,202);
		clock.advance(51);var replay=deletion(b,"ACCOUNT",key);status(replay,202);assertThat(receipt(replay)).isEqualTo(receipt(first));
		code(deletion(b,"ACCOUNT",UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");
	}
	@Test void deletionRequiresExplicitConfirmationAllowedScopeAndRequestProtection()throws Exception{
		Browser b=member();
		for(Object body:List.of(Map.of("scope","AI_DATA","isConfirmed",true),Map.of("scope","LOCATION_DATA","isConfirmed",true),Map.of("scope","ACCOUNT","isConfirmed",false),Map.of("scope","ACCOUNT"),Map.of("scope","ACCOUNT","isConfirmed",true,"userId","private")))
			status(send("POST","/api/v1/me/data-deletions",body,b,UUID.randomUUID()),400);
		status(deletion(b,"USAGE_HISTORY",null),400);
		code(raw("POST","/api/v1/me/data-deletions","{\"scope\":\"USAGE_HISTORY\",\"isConfirmed\":true}",b,UUID.randomUUID(),ORIGIN,"invalid",null),403,"CSRF_INVALID");
		code(deletion(guest(),"USAGE_HISTORY",UUID.randomUUID()),403,"LOGIN_REQUIRED");assertThat(count("deletionJob")).isZero();
	}
	@Test void accountDeletionRequiresRecentReauthAndBlocksUseAndAllOpenMemberCalls()throws Exception{
		Browser b=member();complete(b);Browser other=returningMember();UUID call=create(other);
		code(deletion(b,"ACCOUNT",UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");login(b,"REAUTH",false);clock.advance(300);
		code(deletion(b,"ACCOUNT",UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");login(b,"REAUTH",false);
		var result=deletion(b,"ACCOUNT",UUID.randomUUID());status(result,202);assertThat(result.body().properties()).hasSize(7);
		assertThat(result.text("dueAt")).isEqualTo(clock.instant().plusSeconds(86400).toString());
		assertThat(result.headers().firstValue("Set-Cookie").orElseThrow()).contains("Secure","HttpOnly","SameSite=Lax","Path=/","Max-Age=2592000").doesNotContain("Domain");
		assertThat(result.body().toString()).doesNotContain("receiptToken","cleanupCipher","keyRef","accountSubjectHash");
		assertThat(jdbc.queryForObject("SELECT `endReason` FROM `callSession` WHERE `id`=?",String.class,bin(call))).isEqualTo("DATA_DELETION");
		code(history(other,""),409,"ACCOUNT_DELETION_PENDING");code(deletion(other,"USAGE_HISTORY",UUID.randomUUID()),409,"ACCOUNT_DELETION_PENDING");
	}
	@Test void deletionReplayReissuesSameReceiptAndDuplicateScopeReusesJob()throws Exception{
		Browser b=member();UUID key=UUID.randomUUID();var first=deletion(b,"USAGE_HISTORY",key);status(first,202);
		clock.advance(20);var repeat=deletion(b,"USAGE_HISTORY",key);status(repeat,202);assertThat(receipt(repeat)).isEqualTo(receipt(first));
		var duplicate=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(duplicate,202);assertThat(duplicate.text("id")).isEqualTo(first.text("id"));assertThat(receipt(duplicate)).isEqualTo(receipt(first));
		assertThat(count("deletionJob")).isEqualTo(1);code(deletion(b,"ACCOUNT",key),409,"IDEMPOTENCY_CONFLICT");
		clock.advance(40);code(deletion(b,"USAGE_HISTORY",key),409,"DELETION_RECEIPT_EXPIRED");historyCleanup.run();assertThat(count("deletionJob")).isEqualTo(1);
		assertThat(deletionStatus(first.text("id"),null,receipt(first)).text("status")).isEqualTo("PENDING");
		clock.advance(20);historyCleanup.run();assertThat(deletionStatus(first.text("id"),null,receipt(first)).text("status")).isEqualTo("COMPLETED");
	}
	@Test void historyDeletionBlocksAnyMemberOpenCallAndPreservesNewerDataAndProfile()throws Exception{
		Browser b=composerMember();Browser other=returningMember();UUID old=create(b);status(end(b,old),200);UUID open=create(other);
		code(deletion(b,"USAGE_HISTORY",UUID.randomUUID()),409,"CALL_ALREADY_OPEN");status(end(other,open),200);
		String ref=authRepository.user(userId(b),false).keyRef();byte[] key=userKeys.read(ref);long consents=count("consentEvent");
		var result=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(result,202);historyCleanup.run();assertThat(count("callSession")).isEqualTo(2);
		clock.advance(1);UUID newer=create(b);status(end(b,newer),200);clock.advance(59);historyCleanup.run();
		assertThat(count("callSession")).isEqualTo(1);assertThat(history(b,"").body().path("items").get(0).path("id").asString()).isEqualTo(newer.toString());
		assertThat(userKeys.read(ref)).isEqualTo(key);assertThat(count("emergencyContact")).isEqualTo(1);assertThat(count("consentEvent")).isEqualTo(consents);
		assertThat(deletionStatus(result.text("id"),b,null).text("status")).isEqualTo("COMPLETED");
	}
	@Test void deletionStatusRequiresMatchingReceiptOrOwnerAndHidesOtherJobs()throws Exception{
		Browser b=member();var first=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(first,202);
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("99999",null,null,null,null));Browser other=member();var second=deletion(other,"USAGE_HISTORY",UUID.randomUUID());status(second,202);
		status(deletionStatus(first.text("id"),b,null),200);status(deletionStatus(first.text("id"),null,receipt(first)),200);
		status(deletionStatus(first.text("id"),other,null),404);status(deletionStatus(first.text("id"),null,receipt(second)),404);status(deletionStatus(first.text("id"),null,null),404);
		status(deletionStatus(UUID.randomUUID().toString(),b,receipt(first)),404);
		clock.advance(2592000);status(deletionStatus(first.text("id"),null,receipt(first)),404);
	}
	@Test void ownerCanReadOlderJobsAfterReceiptChangesAndCookieSurvivesAccountDeletion()throws Exception{
		Browser b=member();var history=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(history,202);login(b,"REAUTH",false);
		var account=deletion(b,"ACCOUNT",UUID.randomUUID());status(account,202);status(deletionStatus(history.text("id"),b,receipt(account)),200);
		clock.advance(60);accountsCleanup.run();assertThat(count("appUser")).isZero();
		status(deletionStatus(account.text("id"),b,null),404);assertThat(deletionStatus(account.text("id"),b,receipt(account)).text("status")).isEqualTo("LOCAL_DELETED");
		status(deletionStatus(history.text("id"),null,receipt(account)),404);
	}
	@Test void accountReplayAndCrossEndpointDuplicateKeepFullSixtySecondWindow()throws Exception{
		Browser b=member();login(b,"REAUTH",false);UUID key=UUID.randomUUID();
		var withdrawal=send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,key);status(withdrawal,202);
		clock.advance(50);var duplicate=deletion(b,"ACCOUNT",UUID.randomUUID());status(duplicate,202);
		assertThat(duplicate.text("id")).isEqualTo(withdrawal.text("id"));assertThat(receipt(duplicate)).isEqualTo(receipt(withdrawal));
		clock.advance(10);accountsCleanup.run();assertThat(count("appUser")).isEqualTo(1);
		clock.advance(50);accountsCleanup.run();assertThat(count("appUser")).isZero();
	}
	@Test void historyDeletionRollbackReportsFailedWithoutLosingDataAndAllowsNewRequest()throws Exception{
		Browser b=member();complete(b);UUID call=create(b);status(end(b,call),200);var job=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(job,202);clock.advance(60);
		jdbc.execute("CREATE TRIGGER `synthetic_history_failure` BEFORE UPDATE ON `deletionJob` FOR EACH ROW BEGIN IF NEW.status='COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");
		try{historyCleanup.run();assertThat(count("callSession")).isEqualTo(1);var result=deletionStatus(job.text("id"),b,null);assertThat(result.text("status")).isEqualTo("FAILED");assertThat(result.text("errorCode")).isEqualTo("LOCAL_DELETION_FAILED");}
		finally{jdbc.execute("DROP TRIGGER `synthetic_history_failure`");}
		var retry=deletion(b,"USAGE_HISTORY",UUID.randomUUID());status(retry,202);assertThat(retry.text("id")).isNotEqualTo(job.text("id"));clock.advance(60);historyCleanup.run();assertThat(count("callSession")).isZero();
	}
	@Test void accountRollbackRestoresAccessAndKeyWithoutRestoringEndedCall()throws Exception{
		Browser b=member();complete(b);UUID user=userId(b);String ref=authRepository.user(user,false).keyRef();byte[] key=userKeys.read(ref);login(b,"REAUTH",false);UUID call=create(b);
		var job=deletion(b,"ACCOUNT",UUID.randomUUID());status(job,202);clock.advance(60);
		jdbc.execute("CREATE TRIGGER `synthetic_local_failure` BEFORE UPDATE ON `deletionJob` FOR EACH ROW BEGIN IF NEW.status='LOCAL_DELETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");
		try{accountsCleanup.run();assertThat(userKeys.read(ref)).isEqualTo(key);assertThat(count("appUser")).isEqualTo(1);assertThat(deletionStatus(job.text("id"),b,null).text("status")).isEqualTo("FAILED");
			assertThat(authRepository.user(user,false).status()).isEqualTo("ACTIVE");assertThat(jdbc.queryForObject("SELECT `accountSubjectHash` FROM `deletionJob` WHERE `id`=?",byte[].class,bin(UUID.fromString(job.text("id"))))).isNull();
			assertThat(jdbc.queryForObject("SELECT `endReason` FROM `callSession` WHERE `id`=?",String.class,bin(call))).isEqualTo("DATA_DELETION");status(history(b,""),200);}
		finally{jdbc.execute("DROP TRIGGER `synthetic_local_failure`");}
		status(deletion(b,"ACCOUNT",UUID.randomUUID()),202);
	}
	@Test void firstHundredFailingAccountJobsDoNotStarveLaterHealthyJob()throws Exception{
		assertFailedAccountBatchDoesNotStarve(false);
	}
	@Test void firstHundredUnreadableAccountKeysDoNotStarveLaterHealthyJob()throws Exception{
		assertFailedAccountBatchDoesNotStarve(true);
	}
	void assertFailedAccountBatchDoesNotStarve(boolean unreadableKeys)throws Exception{
		Browser b=member();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());status(job,202);
		clock.advance(60);accountsCleanup.run();UUID target=UUID.fromString(job.text("id"));
		var row=jdbc.queryForMap("SELECT cleanupKeyRef,cleanupCipher FROM deletionJob WHERE id=?",bin(target));
		String ref=(String)row.get("cleanupKeyRef");byte[] secret=userKeys.read(ref);
		var payload=mapper.readTree(crypto.open(secret,"ACCOUNT_CLEANUP:"+target,(byte[])row.get("cleanupCipher")));
		for(int i=0;i<100;i++){
			UUID id=UUID.randomUUID();String subject=String.valueOf(90000+i);
			String failedRef=userKeys.create(UUID.randomUUID());
			byte[] cipher=crypto.seal(userKeys.read(failedRef),"ACCOUNT_CLEANUP:"+id,mapper.writeValueAsBytes(Map.of("subject",subject,"userKeyRef",payload.path("userKeyRef").asString())));
			jdbc.update("INSERT INTO deletionJob (id,scope,status,accountSubjectHash,receiptHash,cleanupCipher,cleanupKeyRef,requestedAt,cutoffAt,dueAt,receiptExpiresAt) VALUES (?,'ACCOUNT','LOCAL_DELETED',?,?,?,?,?,?,?,?)",
				bin(id),crypto.hash("KAKAO_SUBJECT",subject),crypto.hash("DELETION_RECEIPT",id.toString()),cipher,failedRef,time(START.minusSeconds(100-i)),time(START),time(START.plusSeconds(86400)),time(START.plusSeconds(2592000)));
			doThrow(new IllegalStateException("synthetic persistent provider failure")).when(unlink).unlink(subject);
			if(unreadableKeys)doThrow(new IllegalStateException("synthetic key read failure")).when(userKeys).read(failedRef);
		}
		externalCleanup.run();
		verify(unlink,never()).unlink("12345");
		assertThat(jdbc.queryForObject("SELECT status FROM deletionJob WHERE id=?",String.class,bin(target))).isEqualTo("LOCAL_DELETED");
		// Even once all failures are due again, the unattempted healthy job gets a turn.
		clock.advance(61);externalCleanup.run();verify(unlink).unlink("12345");
		assertThat(jdbc.queryForObject("SELECT status FROM deletionJob WHERE id=?",String.class,bin(target))).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM deletionJob WHERE status='LOCAL_DELETED'",Integer.class)).isEqualTo(100);
	}
	@Test void externalCleanupPreservesLegacyEncryptedLease()throws Exception{
		Browser b=member();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());clock.advance(60);accountsCleanup.run();UUID id=UUID.fromString(job.text("id"));
		var row=jdbc.queryForMap("SELECT cleanupKeyRef,cleanupCipher FROM deletionJob WHERE id=?",bin(id));String ref=(String)row.get("cleanupKeyRef");byte[] key=userKeys.read(ref);
		var payload=mapper.readTree(crypto.open(key,"ACCOUNT_CLEANUP:"+id,(byte[])row.get("cleanupCipher")));
		Instant lease=clock.instant().plusSeconds(60);
		jdbc.update("UPDATE deletionJob SET cleanupCipher=?,externalNextAttemptAt=NULL WHERE id=?",crypto.seal(key,"ACCOUNT_CLEANUP:"+id,mapper.writeValueAsBytes(Map.of("subject",payload.path("subject").asString(),"userKeyRef",payload.path("userKeyRef").asString(),"leaseUntil",lease.toString()))),bin(id));
		externalCleanup.run();verifyNoInteractions(unlink);
		assertThat(jdbc.queryForObject("SELECT externalNextAttemptAt FROM deletionJob WHERE id=?",LocalDateTime.class,bin(id))).isEqualTo(LocalDateTime.ofInstant(lease,ZoneOffset.UTC));
		clock.advance(60);externalCleanup.run();assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("COMPLETED");
	}
	@Test void expiredExternalClaimCannotCompleteOverNewerAttempt()throws Exception{
		Browser b=member();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());clock.advance(60);accountsCleanup.run();UUID id=UUID.fromString(job.text("id"));
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempts=new java.util.concurrent.atomic.AtomicInteger();
		doAnswer(inv->{int attempt=attempts.incrementAndGet();if(attempt==1){entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}else if(attempt==2)throw new IllegalStateException("synthetic retry failure");return null;}).when(unlink).unlink("12345");
		try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
			var first=executor.submit(()->externalCleanup.runOne(id));
			try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();clock.advance(60);externalCleanup.runOne(id);}
			finally{release.countDown();}first.get(10,TimeUnit.SECONDS);
		}
		assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("LOCAL_DELETED");
		externalCleanup.runOne(id);assertThat(attempts.get()).isEqualTo(2);
		clock.advance(60);externalCleanup.run();assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("COMPLETED");
	}
	@Test void externalCleanupRetriesAfterLocalDeletionAndReleasesSignupBlockOnlyOnCompletion()throws Exception{
		Browser b=member();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());status(job,202);clock.advance(60);accountsCleanup.run();
		UUID id=UUID.fromString(job.text("id"));String cleanupRef=jdbc.queryForObject("SELECT `cleanupKeyRef` FROM `deletionJob` WHERE `id`=?",String.class,bin(id));
		doThrow(new IllegalStateException("synthetic provider details")).doNothing().when(unlink).unlink("12345");
		externalCleanup.runOne(id);assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("LOCAL_DELETED");assertThat(count("appUser")).isZero();
		Browser retry=bootstrap();assertThat(callback(retry,start(retry,"LOGIN",false)).headers().firstValue("Location")).contains("/login?reason=account_cleanup_pending");
		externalCleanup.runOne(id);verify(unlink,times(1)).unlink("12345");clock.advance(60);externalCleanup.runOne(id);
		var result=deletionStatus(job.text("id"),null,receipt(job));assertThat(result.text("status")).isEqualTo("COMPLETED");assertThat(result.body().path("errorCode").isNull()).isTrue();
		var row=jdbc.queryForMap("SELECT * FROM `deletionJob` WHERE `id`=?",bin(id));for(String field:List.of("cleanupCipher","cleanupKeyRef","accountSubjectHash"))assertThat(row.get(field)).isNull();
		discardQueue.run();assertThatThrownBy(()->userKeys.read(cleanupRef)).isInstanceOf(RuntimeException.class);member();assertThat(count("appUser")).isEqualTo(1);
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings={"ACTIVE","ONBOARDING"})
	void accountRollbackPreservesActiveStatusEvenWhenCompletedSessionsWerePurged(String previousStatus)throws Exception{
		Browser old=member();complete(old);UUID previousSession=sessionId(old);Browser b=member();UUID user=userId(b);
		jdbc.update("DELETE FROM `webSession` WHERE `id`=?",bin(previousSession));login(b,"REAUTH",false);
		assertThat(authRepository.user(user,false).status()).isEqualTo("ACTIVE");
		var job=deletion(b,"ACCOUNT",UUID.randomUUID());status(job,202);UUID id=UUID.fromString(job.text("id"));
		String recoveryRef=jdbc.queryForObject("SELECT `cleanupKeyRef` FROM `deletionJob` WHERE `id`=?",String.class,bin(id));clock.advance(60);
		// Cover encrypted rollback state retained in deletion jobs created by the old server.
		jdbc.update("UPDATE `deletionJob` SET `cleanupCipher`=? WHERE `id`=?",
			crypto.seal(userKeys.read(recoveryRef),"DELETION_ROLLBACK:"+id,previousStatus.getBytes(java.nio.charset.StandardCharsets.UTF_8)),bin(id));
		jdbc.execute("CREATE TRIGGER `synthetic_state_failure` BEFORE UPDATE ON `deletionJob` FOR EACH ROW BEGIN IF NEW.status='LOCAL_DELETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");
		try{accountsCleanup.run();assertThat(authRepository.user(user,false).status()).isEqualTo("ACTIVE");assertThat(deletionStatus(job.text("id"),b,null).text("status")).isEqualTo("FAILED");
			discardQueue.run();assertThatThrownBy(()->userKeys.read(recoveryRef)).isInstanceOf(RuntimeException.class);}
		finally{jdbc.execute("DROP TRIGGER `synthetic_state_failure`");}
	}
	@Test void externalCleanupClaimsOnceAndDoesNotHoldTransactionDuringProviderCall()throws Exception{
		Browser b=member();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());clock.advance(60);accountsCleanup.run();UUID id=UUID.fromString(job.text("id"));
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		doAnswer(inv->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return null;}).when(unlink).unlink("12345");
		try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
			var first=executor.submit(()->externalCleanup.runOne(id));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
			try{externalCleanup.runOne(id);status(deletionStatus(job.text("id"),null,receipt(job)),200);verify(unlink,times(1)).unlink("12345");}
			finally{release.countDown();}first.get(10,TimeUnit.SECONDS);
		}
		assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("COMPLETED");
	}
	@Test void externalCleanupRetriesPersonalKeyDeletionBeforeCompletingJob()throws Exception{
		Browser b=member();String ref=authRepository.user(userId(b),false).keyRef();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());status(job,202);
		doThrow(new IllegalStateException("synthetic key failure")).when(userKeys).discard(ref);clock.advance(60);accountsCleanup.run();
		UUID id=UUID.fromString(job.text("id"));externalCleanup.runOne(id);verifyNoInteractions(unlink);
		assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("LOCAL_DELETED");assertThat(userKeys.read(ref)).hasSize(32);
		doCallRealMethod().when(userKeys).discard(ref);clock.advance(60);externalCleanup.runOne(id);
		assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("COMPLETED");assertThatThrownBy(()->userKeys.read(ref)).isInstanceOf(RuntimeException.class);
	}
	@Test void externalCompletionDbFailureKeepsCleanupKeyAndRetriesWithoutReportingFailed()throws Exception{
		Browser b=member();login(b,"REAUTH",false);var job=deletion(b,"ACCOUNT",UUID.randomUUID());status(job,202);clock.advance(60);accountsCleanup.run();UUID id=UUID.fromString(job.text("id"));
		String ref=jdbc.queryForObject("SELECT `cleanupKeyRef` FROM `deletionJob` WHERE `id`=?",String.class,bin(id));
		jdbc.execute("CREATE TRIGGER `synthetic_external_failure` BEFORE UPDATE ON `deletionJob` FOR EACH ROW BEGIN IF NEW.status='COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");
		try{externalCleanup.runOne(id);assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("LOCAL_DELETED");assertThat(userKeys.read(ref)).hasSize(32);}
		finally{jdbc.execute("DROP TRIGGER `synthetic_external_failure`");}
		clock.advance(60);externalCleanup.runOne(id);verify(unlink,times(2)).unlink("12345");assertThat(deletionStatus(job.text("id"),null,receipt(job)).text("status")).isEqualTo("COMPLETED");
	}
	@Test void chapterSixOpenApiDescribesReceiptAsAlternativeAndGetWithoutCsrf()throws Exception{
		var spec=send("GET","/v3/api-docs",null,null,null).body();var paths=spec.path("paths");
		for(String path:List.of("/api/v1/me/usage-history","/api/v1/data-deletions/{jobId}"))assertThat(paths.path(path).path("get").path("parameters").toString()).doesNotContain("X-CSRF-Token");
		var security=paths.path("/api/v1/data-deletions/{jobId}").path("get").path("security");assertThat(security.size()).isEqualTo(2);assertThat(security.toString()).contains("webSession","deletionReceipt");
		assertThat(paths.path("/api/v1/me/data-deletions").path("post").path("responses").has("202")).isTrue();
	}

	@Test void composerDoesNotReturnPrivateMaterialsWhenSessionExpiresDuringKeyRead() throws Exception {
		Browser b=composerMember(); UUID session=sessionId(b);
		String ref=authRepository.user(userId(b),false).keyRef();
		jdbc.update("UPDATE `webSession` SET `expiresAt`=? WHERE `id`=?",time(START.plusSeconds(1)),bin(session));
		doAnswer(invocation -> { byte[] key=(byte[])invocation.callRealMethod(); clock.advance(1); return key; }).when(userKeys).read(ref);
		var result=composer(b,""); code(result,401,"SESSION_EXPIRED");
		assertThat(result.body().toString()).doesNotContain("recipients","홍길동","01087654321");
		assertThat(authRepository.session(session).status()).isEqualTo("EXPIRED");
	}
}

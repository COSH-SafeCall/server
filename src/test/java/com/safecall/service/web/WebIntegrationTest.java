package com.safecall.service.web;

import static com.safecall.service.auth.repository.AuthRepository.bin;
import static com.safecall.service.auth.repository.AuthRepository.time;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.call.gemini.GeminiClient;
import com.safecall.service.common.crypto.FileUserKeyStore;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.crypto.UserKeyStore;
import com.safecall.service.user.service.AccountLocalCleanup;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("mysql")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebIntegrationTest {
	private static final Instant START = Instant.parse("2026-09-16T00:00:00Z");
	private static final String ORIGIN = "https://safecall.test";

	@LocalServerPort private int port;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JsonMapper mapper;
	@Autowired private MutableClock clock;
	@Autowired private SecretCrypto crypto;
	@Autowired private AuthRepository authRepository;
	@Autowired private AccountLocalCleanup accountCleanup;
	@MockitoBean private GeminiClient gemini;

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry registry) {
		String url = System.getenv("AUTH_TEST_DB_URL");
		if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?!3306/)[0-9]+/safecall_auth_test_[0-9a-f]{16}\\?.*")) {
			throw new IllegalStateException("scripts/verify_auth.py로 생성한 일회용 DB를 사용해야 합니다.");
		}
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.hikari.connection-timeout", () -> 30000);
		registry.add("app.crypto.hmac-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
		registry.add("app.crypto.response-secret", () -> Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
		registry.add("app.crypto.csrf-secret", () -> Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes()));
		registry.add("app.crypto.key-directory", () -> System.getenv("AUTH_TEST_KEY_DIRECTORY"));
		registry.add("app.web.origin", () -> ORIGIN);
		registry.add("app.telemetry.web-version", () -> "synthetic-web-v8");
		registry.add("app.gemini.connection-ttl-seconds", () -> 600);
		registry.add("app.gemini.validated-model", () -> "models/synthetic-live");
		registry.add("app.gemini.validation-ref", () -> "synthetic-test-only");
		registry.add("app.gemini.model-max-seconds", () -> 600);
		registry.add("app.auth.requests-per-minute", () -> 1000);
		registry.add("app.auth.cleanup-delay-ms", () -> 3600000);
		registry.add("app.call.worker-delay-ms", () -> 3600000);
		registry.add("app.crypto.discard-delay-ms", () -> 3600000);
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
	@Test
	void browserPermissionsAreStoredPerMember() throws Exception {
		Browser first = member();
		Browser second = member();
		Result saved = send("POST", "/api/v1/me/permissions", Map.of("permissions", List.of(
			Map.of("code", "MICROPHONE", "status", "GRANTED"),
			Map.of("code", "LOCATION", "status", "DENIED"))), first, UUID.randomUUID());
		status(saved, 200);
		Result untouched = send("GET", "/api/v1/me/permissions", null, second, null);
		status(untouched, 200);
		assertThat(saved.body().path("items").get(0).path("status").asText()).isEqualTo("GRANTED");
		assertThat(saved.body().path("items").get(1).path("status").asText()).isEqualTo("DENIED");
		assertThat(untouched.body().path("items").get(0).path("status").asText()).isEqualTo("NOT_DETERMINED");
		assertThat(untouched.body().path("items").get(1).path("status").asText()).isEqualTo("NOT_DETERMINED");
	}

	@Test
	void fixedProfileAndGuardianValuesMayRepeatAcrossMembers() throws Exception {
		for (Browser browser : List.of(member(), member())) {
			Result profile = send("GET", "/api/v1/me/profile", null, browser, null);
			status(profile, 200);
			status(send("PATCH", "/api/v1/me/profile", Map.of(
				"name", "홍길동", "gender", "FEMALE", "birthDate", "2000-01-01",
				"phone", "010-0000-0000", "isConfirmed", true,
				"expectedVersion", profile.body().path("version").asLong()), browser, UUID.randomUUID()), 200);
			status(send("POST", "/api/v1/me/emergency-contacts", Map.of(
				"name", "보호자", "relationship", "가족", "phone", "010-1111-1111"), browser, UUID.randomUUID()), 201);
	@TestConfiguration
	static class Configuration {
		@Bean @Primary MutableClock testClock() { return new MutableClock(); }
		@Bean UserKeyStore testKeys(SecretCrypto crypto) {
			return new FileUserKeyStore(System.getenv("AUTH_TEST_KEY_DIRECTORY"), crypto);
		}
	}

	static class MutableClock extends Clock {
		private final AtomicReference<Instant> value = new AtomicReference<>(START);
		void reset() { value.set(START); }
		void advance(long seconds) { value.updateAndGet(current -> current.plusSeconds(seconds)); }
		@Override public Instant instant() { return value.get(); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
	}

	private record Result(int status, JsonNode body, HttpHeaders headers) {
		String text(String field) { return body.path(field).asString(); }
	}

	private class Browser {
		private String cookie;
		private String csrf;
		private final String page = crypto.randomToken();
	}

	@BeforeEach
	void reset() {
		for (String table : List.of("keyDiscardJob", "deletionJob", "apiIdempotency", "operationEvent", "callEvent",
			"connectionGrant", "callSession", "emergencyContact", "webSession", "userSetting", "appUser",
			"rateBucket", "personaPrompt", "promptRelease")) {
			jdbc.update("DELETE FROM `" + table + "`");
		}
		clock.reset();
		when(gemini.isConfigured()).thenReturn(true);
		when(gemini.issue(any())).thenReturn("auth_tokens/synthetic-only");
		UUID release = UUID.randomUUID();
		jdbc.update("INSERT INTO `promptRelease` (`id`,`version`,`status`,`modelId`,`apiVersion`,`safetyInstruction`,`demographicRules`,`guestInstruction`,`validationRef`,`publishedAt`) VALUES (?,1,'PUBLISHED','models/synthetic-live','v1beta','safety','{age} {gender}','neutral','synthetic-test-only',?)",
			bin(release), time(START.minusSeconds(60)));
		for (String scenario : List.of("FOLLOWED", "UNSAFE_TAXI", "STRANGER_NEARBY", "WALKING_ALONE")) {
			for (String counterpart : List.of("FATHER", "MOTHER", "FRIEND")) {
				jdbc.update("INSERT INTO `personaPrompt` (`releaseId`,`scenarioCode`,`counterpartCode`,`baseInstruction`,`voiceId`) VALUES (?,?,?,'family conversation','Puck')",
					bin(release), scenario, counterpart);
			}
		}
	}

	@Test
	void virtualLoginCreatesDifferentMembersEveryTime() throws Exception {
		Browser first = member();
		Browser second = member();
		assertThat(userId(first)).isNotEqualTo(userId(second));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser`", Integer.class)).isEqualTo(2);
	}

	@Test
	void browserPermissionsAreStoredPerMember() throws Exception {
		Browser first = member();
		Browser second = member();
		Result saved = send("POST", "/api/v1/me/permissions", Map.of("permissions", List.of(
			Map.of("code", "MICROPHONE", "status", "GRANTED"),
			Map.of("code", "LOCATION", "status", "DENIED"))), first, UUID.randomUUID());
		status(saved, 200);
		Result untouched = send("GET", "/api/v1/me/permissions", null, second, null);
		status(untouched, 200);
		assertThat(saved.body().path("items").get(0).path("status").asText()).isEqualTo("GRANTED");
		assertThat(saved.body().path("items").get(1).path("status").asText()).isEqualTo("DENIED");
		assertThat(untouched.body().path("items").get(0).path("status").asText()).isEqualTo("NOT_DETERMINED");
		assertThat(untouched.body().path("items").get(1).path("status").asText()).isEqualTo("NOT_DETERMINED");
	}

	@Test
	void fixedProfileAndGuardianValuesMayRepeatAcrossMembers() throws Exception {
		for (Browser browser : List.of(member(), member())) {
			Result profile = send("GET", "/api/v1/me/profile", null, browser, null);
			status(profile, 200);
			status(send("PATCH", "/api/v1/me/profile", Map.of(
				"name", "홍길동", "gender", "FEMALE", "birthDate", "2000-01-01",
				"phone", "010-0000-0000", "isConfirmed", true,
				"expectedVersion", profile.body().path("version").asLong()), browser, UUID.randomUUID()), 200);
			status(send("POST", "/api/v1/me/emergency-contacts", Map.of(
				"name", "보호자", "relationship", "가족", "phone", "010-1111-1111"), browser, UUID.randomUUID()), 201);
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser` WHERE `phoneHash` IS NOT NULL", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `emergencyContact`", Integer.class)).isEqualTo(2);
	}

	@Test
	void guestCanStartQuickCallWithoutProfileOrContact() throws Exception {
		Result call = createCall(guest());
		status(call, 202);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser`", Integer.class)).isZero();
		assertThat(call.text("startMode")).isEqualTo("QUICK");
	}

	@Test
	void memberCanUseCallWithoutConsentRecords() throws Exception {
		Browser member = member();
		Result profile = send("GET", "/api/v1/me/profile", null, member, null);
		status(send("PATCH", "/api/v1/me/profile", Map.of(
			"name", "홍길동", "gender", "FEMALE", "birthDate", "2000-01-01",
			"phone", "010-0000-0000", "isConfirmed", true,
			"expectedVersion", profile.body().path("version").asLong()), member, UUID.randomUUID()), 200);
		status(createCall(member), 202);
		assertThat(tableCount("consentEvent")).isZero();
	}

	@Test
	void accountDeletionNeedsNoReauthenticationAndRemovesLocalAccount() throws Exception {
		Browser member = member();
		UUID userId = userId(member);
		Result deletion = send("POST", "/api/v1/me/data-deletions", Map.of("scope", "ACCOUNT", "isConfirmed", true), member, UUID.randomUUID());
		status(deletion, 202);
		clock.advance(61);
		accountCleanup.run();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser` WHERE `id`=?", Integer.class, bin(userId))).isZero();
		assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob` WHERE `id`=?", String.class,
			bin(UUID.fromString(deletion.text("id"))))).isEqualTo("COMPLETED");
	}

	@Test
	void removedOauthAndConsentSchemaDoesNotExist() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('oauthAttempt','oauthConsent','consentEvent','serviceDocument')", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='appUser' AND column_name LIKE 'kakao%') OR (table_name='webSession' AND column_name='sensitiveVerifiedAt'))", Integer.class)).isZero();
	}

	private Browser bootstrap() throws Exception {
		Browser browser = new Browser();
		Result result = send("GET", "/api/v1/auth/session", null, browser, null);
		status(result, 200);
		cookie(browser, result);
		browser.csrf = result.text("csrfToken");
		return browser;
	}

	private Browser member() throws Exception {
		Browser browser = bootstrap();
		Result result = send("POST", "/api/v1/auth/virtual", Map.of(), browser, null);
		status(result, 200);
		cookie(browser, result);
		browser.csrf = result.text("csrfToken");
		assertThat(result.text("kind")).isEqualTo("MEMBER");
		return browser;
	}

	private Browser guest() throws Exception {
		Browser browser = bootstrap();
		Result result = send("POST", "/api/v1/auth/guest", Map.of(), browser, null);
		status(result, 200);
		cookie(browser, result);
		browser.csrf = result.text("csrfToken");
		assertThat(result.text("kind")).isEqualTo("GUEST");
		return browser;
	}

	private Result createCall(Browser browser) throws Exception {
		return send("POST", "/api/v1/calls", Map.of(
			"clientCallId", UUID.randomUUID(), "startMode", "QUICK", "scenarioCode", "FOLLOWED",
			"counterpartCode", "FATHER", "microphonePermission", "GRANTED"), browser, UUID.randomUUID());
	}

	private UUID userId(Browser browser) {
		return authRepository.byCookie(crypto.hash("WEB_SESSION", browser.cookie)).userId();
	}

	private int tableCount(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
	}

	private Result send(String method, String path, Object body, Browser browser, UUID idempotencyKey) throws Exception {
		String json = body == null ? null : mapper.writeValueAsString(body);
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.header("Accept", "application/json").header("Origin", ORIGIN);
		if (json != null) request.header("Content-Type", "application/json");
		if (browser != null) {
			if (browser.cookie != null) request.header("Cookie", "__Host-safecall-session=" + browser.cookie);
			if (browser.csrf != null) request.header("X-CSRF-Token", browser.csrf);
			request.header("X-Call-Page-Key", browser.page);
		}
		if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey.toString());
		request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		JsonNode responseBody = response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
		return new Result(response.statusCode(), responseBody, response.headers());
	}

	private void cookie(Browser browser, Result result) {
		for (String value : result.headers().allValues("Set-Cookie")) {
			if (value.startsWith("__Host-safecall-session=")) {
				browser.cookie = value.substring(value.indexOf('=') + 1, value.indexOf(';'));
			}
		}
	}

	private void status(Result result, int expected) {
		assertThat(result.status()).as("응답 본문: %s", result.body()).isEqualTo(expected);
	}
}

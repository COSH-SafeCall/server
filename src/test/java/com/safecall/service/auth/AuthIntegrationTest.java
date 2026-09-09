package com.safecall.service.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.safecall.service.auth.repository.AuthRepository.bin;
import static com.safecall.service.auth.repository.AuthRepository.time;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.kakao.KakaoClient;
import com.safecall.service.auth.kakao.KakaoClient.KakaoIdentity;
import com.safecall.service.auth.service.AuthMaintenance;
import com.safecall.service.common.crypto.SecretCrypto;

@Tag("mysql")
@org.springframework.test.context.ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {
	private static final Instant START = Instant.parse("2026-09-08T05:00:00Z");
	@LocalServerPort int port;
	@Autowired JdbcTemplate jdbc;
	@Autowired JsonMapper mapper;
	@Autowired MutableClock clock;
	@Autowired SecretCrypto crypto;
	@Autowired AuthMaintenance maintenance;
	@MockitoBean KakaoClient kakao;
	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	com.safecall.service.auth.repository.AuthRepository repository;
	private final HttpClient http = HttpClient.newHttpClient();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		String url = System.getenv("AUTH_TEST_DB_URL");
		if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?!3306/)[0-9]+/safecall_auth_test_[0-9a-f]{16}\\?.*")) {
			throw new IllegalStateException("Run scripts/verify_auth.py with a disposable MySQL database.");
		}
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("app.jwt.secret", () -> "synthetic-signing-key-of-at-least-32-bytes");
		registry.add("app.crypto.hmac-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
		registry.add("app.crypto.response-secret", () -> Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		registry.add("app.crypto.key-directory", () -> System.getenv("AUTH_TEST_KEY_DIRECTORY"));
		registry.add("app.oauth.kakao.app-id", () -> 123);
		registry.add("app.auth.requests-per-minute", () -> 1000);
		registry.add("app.auth.cleanup-delay-ms", () -> 3600000);
	}
	@TestConfiguration
	static class Config {
		@Bean @Primary MutableClock testClock() { return new MutableClock(); }
	}
	static class MutableClock extends Clock {
		private final AtomicReference<Instant> value = new AtomicReference<>(START);
		void set(Instant instant) { value.set(instant); }
		void advance(long seconds) { value.updateAndGet(now -> now.plusSeconds(seconds)); }
		@Override public Instant instant() { return value.get(); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
	}
	@BeforeEach
	void reset() {
		for (String table : List.of("deletionJob","deviceSession","appUser","deviceInstallation","rateBucket","serviceDocument","personaPrompt","promptRelease")) {
			jdbc.update("DELETE FROM `" + table + "`");
		}
		clock.set(START);
		when(kakao.verify(anyString())).thenReturn(new KakaoIdentity("12345", "홍길동", "MALE", LocalDate.of(2000,1,1), "01012345678"));
	}

	record Result(int status, JsonNode body, HttpHeaders headers) {
		String text(String... path) {
			JsonNode node = body;
			for (String part : path) node = node.path(part);
			return node.asString();
		}
	}
	private Result send(String method, String path, Object body, String access, UUID key) throws Exception {
		return raw(method, path, body == null ? null : mapper.writeValueAsString(body), access, key, "application/json");
	}
	private Result raw(String method, String path, String body, String access, UUID key, String type) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Accept","application/json");
		if (body != null) request.header("Content-Type",type);
		if (access != null) request.header("Authorization","Bearer " + access);
		if (key != null) request.header("Idempotency-Key",key.toString());
		request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		return new Result(result.statusCode(), result.body().isBlank() ? null : mapper.readTree(result.body()), result.headers());
	}
	private Map<String,Object> guestBody() {
		return new LinkedHashMap<>(Map.of("installationId",UUID.randomUUID().toString(),"platform","ANDROID",
			"appVersion","1.0.0","osVersion","16","bootstrapSecret",crypto.randomToken()));
	}
	private Map<String,Object> kakaoBody() {
		return new LinkedHashMap<>(Map.of("installationId",UUID.randomUUID().toString(),"platform","ANDROID",
			"appVersion","1.0.0","osVersion","16","kakaoAccessToken","synthetic-kakao-token"));
	}
	private Result guest() throws Exception {
		var result = send("POST","/api/v1/auth/guest",guestBody(),null,UUID.randomUUID());
		assertThat(result.status()).as("guest response code: %s",result.text("code")).isEqualTo(201);
		return result;
	}
	private Result member() throws Exception {
		var result = send("POST","/api/v1/auth/kakao",kakaoBody(),null,UUID.randomUUID());
		assertThat(result.status()).as("kakao response code: %s",result.text("code")).isEqualTo(200);
		return result;
	}
	private String access(Result auth) { return auth.text("tokens","accessToken"); }
	private UUID sessionId(Result auth) { return UUID.fromString(auth.text("session","sessionId")); }
	private UUID userId(Result auth) { return UUID.fromString(auth.text("profile","userId")); }
	private Result refresh(String token, UUID key) throws Exception {
		return send("POST","/api/v1/auth/refresh",Map.of("refreshToken",token),null,key);
	}

	@Test void swaggerListsOnlyImplementedApisWithUsableAuthenticationAndExamples() throws Exception {
		var document = send("GET","/v3/api-docs",null,null,null);
		assertThat(document.status()).isEqualTo(200);
		assertThat(document.text("openapi")).startsWith("3.0.");
		JsonNode paths = document.body().path("paths");
		assertThat(paths.size()).isEqualTo(14);
		JsonNode guest = paths.path("/api/v1/auth/guest").path("post");
		assertThat(guest.path("operationId").asString()).isEqualTo("A01");
		assertThat(guest.path("responses").has("201")).isTrue();
		assertThat(guest.path("responses").has("200")).isFalse();
		assertThat(paths.path("/api/v1/auth/logout").path("post").path("responses").path("204").has("content")).isFalse();
		assertThat(paths.path("/api/v1/session").path("get").path("security").get(0).has("accessToken")).isTrue();
		assertThat(paths.path("/api/v1/auth/refresh").path("post").path("security").get(0).size()).isZero();
		for (JsonNode parameter : guest.path("parameters")) assertThat(parameter.path("name").asString()).isNotEqualTo("Authorization");
		JsonNode schemas = document.body().path("components").path("schemas");
		assertThat(schemas.path("SessionView").path("properties").has("isReconsentRequired")).isTrue();
		assertThat(schemas.path("OnboardingView").path("properties").has("isAdvanceAllowed")).isTrue();
		assertThat(schemas.path("ErrorResponse").path("properties").size()).isEqualTo(6);
		assertThat(schemas.path("AuthResponse").path("properties").path("profile").path("nullable").asBoolean()).isTrue();
		assertThat(schemas.path("GuestRequest").path("additionalProperties").asBoolean(true)).isFalse();
		assertThat(schemas.path("AdvanceRequest").path("properties").path("step").path("enum").toString()).doesNotContain("COMPLETE");
		JsonNode example = guest.path("requestBody").path("content").path("application/json").path("examples").path("guest").path("value");
		var created = send("POST","/api/v1/auth/guest",example,null,UUID.randomUUID());
		assertThat(created.status()).isEqualTo(201);
		JsonNode advance = paths.path("/api/v1/onboarding/advance").path("post");
		JsonNode step = advance.path("requestBody").path("content").path("application/json").path("examples").path("guestPermissions").path("value");
		assertThat(send("POST","/api/v1/onboarding/advance",step,access(created),UUID.randomUUID()).text("step")).isEqualTo("SOS_GUIDE");
	}
	@Test void swaggerUiAndConfigurationAreServedWithoutAuthentication() throws Exception {
		var result = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/swagger-ui/index.html")).GET().build(),HttpResponse.BodyHandlers.ofString());
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.body()).contains("swagger-ui-bundle.js");
		var config = send("GET","/v3/api-docs/swagger-config",null,null,null);
		assertThat(config.status()).isEqualTo(200);
		assertThat(config.text("url")).isEqualTo("/v3/api-docs");
		assertThat(config.body().path("persistAuthorization").asBoolean(true)).isFalse();
	}
	@Test void guestCreatesNoMemberAndUsesExpectedContract() throws Exception {
		var auth = guest();
		assertThat(auth.text("session","kind")).isEqualTo("GUEST");
		assertThat(auth.body().path("profile").isNull()).isTrue();
		assertThat(auth.body().path("session").path("userId").isNull()).isTrue();
		assertThat(auth.body().path("session").has("isReconsentRequired")).isTrue();
		assertThat(auth.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(auth.headers().firstValue("X-Request-Id")).isPresent();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser`",Integer.class)).isZero();
		assertThat(send("GET","/api/v1/session",null,access(auth),null).status()).isEqualTo(200);
	}
	@Test void initialResponseLossReplaysWithoutNewTokenAndRejectsConflicts() throws Exception {
		var body = guestBody(); UUID key = UUID.randomUUID();
		var first = send("POST","/api/v1/auth/guest",body,null,key);
		var replay = send("POST","/api/v1/auth/guest",body,null,key);
		assertThat(replay.status()).isEqualTo(201);
		assertThat(replay.body()).isEqualTo(first.body());
		body.put("appVersion","2");
		assertThat(send("POST","/api/v1/auth/guest",body,null,key).text("code")).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `deviceSession`",Integer.class)).isEqualTo(1);
	}
	@Test void stolenInstallationCannotReplaceActiveSession() throws Exception {
		var body = guestBody(); var first = send("POST","/api/v1/auth/guest",body,null,UUID.randomUUID());
		body.put("bootstrapSecret",crypto.randomToken());
		assertThat(send("POST","/api/v1/auth/guest",body,null,UUID.randomUUID()).status()).isEqualTo(401);
		body.put("currentRefreshToken",first.text("tokens","refreshToken"));
		var replacement = send("POST","/api/v1/auth/guest",body,null,UUID.randomUUID());
		assertThat(replacement.status()).isEqualTo(201);
		assertThat(send("GET","/api/v1/session",null,access(first),null).status()).isEqualTo(401);
	}
	@Test void authReplayExpiresAndIsNotReissued() throws Exception {
		var body = guestBody(); UUID key = UUID.randomUUID();
		send("POST","/api/v1/auth/guest",body,null,key);
		clock.advance(61); maintenance.cleanup();
		assertThat(send("POST","/api/v1/auth/guest",body,null,key).text("code")).isEqualTo("AUTH_REPLAY_EXPIRED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `apiIdempotency` WHERE `responseCipher` IS NOT NULL",Integer.class)).isZero();
	}
	@Test void rotationRevokesOldAccessAndReplaysBeforeConsumedCheck() throws Exception {
		var auth = guest(); UUID key = UUID.randomUUID(); String old = auth.text("tokens","refreshToken");
		var rotated = refresh(old,key);
		assertThat(rotated.status()).isEqualTo(200);
		assertThat(refresh(old,key).body()).isEqualTo(rotated.body());
		assertThat(send("GET","/api/v1/session",null,access(auth),null).status()).isEqualTo(401);
		assertThat(send("GET","/api/v1/session",null,rotated.text("accessToken"),null).status()).isEqualTo(200);
	}
	@Test void oldGenerationCannotReplayAfterSuccessorRotates() throws Exception {
		var auth = guest(); UUID firstKey = UUID.randomUUID();
		var first = refresh(auth.text("tokens","refreshToken"),firstKey);
		assertThat(refresh(first.text("refreshToken"),UUID.randomUUID()).status()).isEqualTo(200);
		assertThat(refresh(auth.text("tokens","refreshToken"),firstKey).text("code")).isEqualTo("REFRESH_REPLAY_EXPIRED");
	}
	@Test void refreshReuseCommitsSessionRevocationDespite401() throws Exception {
		var auth = guest(); String old = auth.text("tokens","refreshToken");
		var first = refresh(old,UUID.randomUUID());
		assertThat(refresh(old,UUID.randomUUID()).text("code")).isEqualTo("TOKEN_REUSED");
		assertThat(jdbc.queryForObject("SELECT `status` FROM `deviceSession` WHERE `id`=?",String.class,bin(sessionId(auth)))).isEqualTo("REVOKED");
		assertThat(send("GET","/api/v1/session",null,first.text("accessToken"),null).status()).isEqualTo(401);
	}
	@Test void refreshReplayExpiresAfter30Seconds() throws Exception {
		var auth = guest(); UUID key = UUID.randomUUID();
		refresh(auth.text("tokens","refreshToken"),key);
		clock.advance(31);
		assertThat(refresh(auth.text("tokens","refreshToken"),key).text("code")).isEqualTo("REFRESH_REPLAY_EXPIRED");
	}
	@Test void guestRotationNeverExtends24HourLifetime() throws Exception {
		var auth = guest();
		clock.advance(86350);
		var rotated = refresh(auth.text("tokens","refreshToken"),UUID.randomUUID());
		assertThat(rotated.status()).isEqualTo(200);
		assertThat(rotated.text("accessExpiresAt")).isEqualTo(auth.text("tokens","refreshExpiresAt"));
		assertThat(rotated.text("refreshExpiresAt")).isEqualTo(auth.text("tokens","refreshExpiresAt"));
		clock.advance(51);
		assertThat(refresh(rotated.text("refreshToken"),UUID.randomUUID()).status()).isEqualTo(401);
		assertThat(jdbc.queryForObject("SELECT `status` FROM `deviceSession` WHERE `id`=?",String.class,bin(sessionId(auth)))).isEqualTo("EXPIRED");
	}
	@Test void guestOnboardingForbidsLocationAndReplaysBeforeVersionCheck() throws Exception {
		var auth = guest(); String access = access(auth); UUID key = UUID.randomUUID();
		var body = Map.of("step","PERMISSIONS","expectedVersion",1,"permissionReview",Map.of("microphone","DENIED"));
		var bad = Map.of("step","PERMISSIONS","expectedVersion",1,"permissionReview",Map.of("microphone","DENIED","location","DENIED"));
		assertThat(send("POST","/api/v1/onboarding/advance",bad,access,UUID.randomUUID()).status()).isEqualTo(400);
		var next = send("POST","/api/v1/onboarding/advance",body,access,key);
		assertThat(next.text("step")).isEqualTo("SOS_GUIDE");
		assertThat(send("POST","/api/v1/onboarding/advance",body,access,key).status()).isEqualTo(200);
		assertThat(send("POST","/api/v1/onboarding/advance",body,access,UUID.randomUUID()).text("code")).isEqualTo("VERSION_CONFLICT");
		var done = send("POST","/api/v1/onboarding/advance",Map.of("step","SOS_GUIDE","expectedVersion",2),access,UUID.randomUUID());
		assertThat(done.text("step")).isEqualTo("COMPLETE");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `operationEvent` WHERE `code`='MICROPHONE_PERMISSION_REVIEWED'",Integer.class)).isEqualTo(1);
	}
	@Test void memberProfileIsEncryptedAndMissingConfirmationBlocksProgress() throws Exception {
		var auth = member();
		assertThat(auth.text("profile","phone")).isEqualTo("01012345678");
		assertThat(auth.text("profile","genderSource")).isEqualTo("KAKAO");
		byte[] cipher = jdbc.queryForObject("SELECT `nameCipher` FROM `appUser` WHERE `id`=?",byte[].class,bin(userId(auth)));
		assertThat(cipher).isNotEqualTo("홍길동".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var onboarding = send("GET","/api/v1/onboarding",null,access(auth),null);
		assertThat(onboarding.body().path("requiredMissingFields").toString()).contains("confirmedAt");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","PROFILE","expectedVersion",1),access(auth),UUID.randomUUID()).status()).isEqualTo(422);
	}
	private void grantConsents(UUID user) {
		for (String code : List.of("PRIVACY_PROCESSING","AI_CALL")) {
			jdbc.update("INSERT INTO `serviceDocument` (`code`,`version`,`title`,`body`,`isConsent`,`isRequired`,`isCurrent`,`publishedAt`) VALUES (?,1,'test','test',1,1,1,?)",code,time(START));
			jdbc.update("INSERT INTO `consentEvent` (`id`,`userId`,`documentCode`,`documentVersion`,`action`,`recordedAt`) VALUES (?,?,?,1,'GRANTED',?)",
				bin(UUID.randomUUID()),bin(user),code,time(START));
		}
	}
	@Test void memberOnboardingAllowsZeroContactsButRequiresCurrentConsents() throws Exception {
		var auth = member(); String access = access(auth);
		jdbc.update("UPDATE `appUser` SET `profileConfirmedAt`=? WHERE `id`=?",time(START),bin(userId(auth)));
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","PROFILE","expectedVersion",1),access,UUID.randomUUID()).text("step")).isEqualTo("CONTACTS");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","CONTACTS","expectedVersion",2),access,UUID.randomUUID()).text("step")).isEqualTo("CONSENTS");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","CONSENTS","expectedVersion",3),access,UUID.randomUUID()).status()).isEqualTo(403);
		grantConsents(userId(auth));
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","CONSENTS","expectedVersion",3),access,UUID.randomUUID()).text("step")).isEqualTo("PERMISSIONS");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","PERMISSIONS","expectedVersion",4,"permissionReview",Map.of("microphone","DENIED","location","DENIED")),access,UUID.randomUUID()).text("step")).isEqualTo("SOS_GUIDE");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","SOS_GUIDE","expectedVersion",5),access,UUID.randomUUID()).text("step")).isEqualTo("MESSAGE_TEST");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","MESSAGE_TEST","expectedVersion",6),access,UUID.randomUUID()).status()).isEqualTo(400);
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","MESSAGE_TEST","expectedVersion",6,"testDecision","SKIP"),access,UUID.randomUUID()).text("step")).isEqualTo("COMPLETE");
		assertThat(jdbc.queryForObject("SELECT `status` FROM `appUser` WHERE `id`=?",String.class,bin(userId(auth)))).isEqualTo("ACTIVE");
	}
	@Test void existingMemberOnNewDeviceReusesAccountAndStartsProfileReview() throws Exception {
		var first = member(); grantConsents(userId(first));
		jdbc.update("UPDATE `appUser` SET `status`='ACTIVE',`profileConfirmedAt`=? WHERE `id`=?",time(START),bin(userId(first)));
		var second = member();
		assertThat(userId(second)).isEqualTo(userId(first));
		assertThat(second.text("session","onboardingStep")).isEqualTo("PROFILE");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","PROFILE","expectedVersion",1),access(second),UUID.randomUUID()).text("step")).isEqualTo("PERMISSIONS");
		assertThat(send("GET","/api/v1/session",null,access(first),null).status()).isEqualTo(200);
	}
	@Test void reloginDoesNotRestoreWithdrawnDemographics() throws Exception {
		var first = member();
		jdbc.update("UPDATE `appUser` SET `genderCipher`=NULL,`genderSource`='UNKNOWN',`birthDateCipher`=NULL,`birthDateSource`='UNKNOWN' WHERE `id`=?",bin(userId(first)));
		var second = member();
		assertThat(second.text("profile","gender")).isEqualTo("UNKNOWN");
		assertThat(second.body().path("profile").path("birthDate").isNull()).isTrue();
	}
	@Test void pendingExternalDeletionBlocksReRegistrationWithoutUserRow() throws Exception {
		jdbc.update("""
			INSERT INTO `deletionJob` (`id`,`scope`,`accountSubjectHash`,`status`,`receiptHash`,`cutoffAt`,`dueAt`,`receiptExpiresAt`)
			VALUES (?,'ACCOUNT',?,'LOCAL_DELETED',?,?,?,?)
			""",bin(UUID.randomUUID()),crypto.hash("KAKAO_SUBJECT","12345"),crypto.hash("RECEIPT","synthetic"),time(START),time(START.plusSeconds(86400)),time(START.plusSeconds(86400)));
		assertThat(send("POST","/api/v1/auth/kakao",kakaoBody(),null,UUID.randomUUID()).text("code")).isEqualTo("ACCOUNT_DELETION_PENDING");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser`",Integer.class)).isZero();
	}
	@Test void deletionCommittingBetweenLoginReadsStillBlocksSignup() throws Exception {
		var auth = member();
		var checked = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var isFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
		doAnswer(invocation -> {
			boolean result = (boolean) invocation.callRealMethod();
			if (isFirst.getAndSet(false)) {
				checked.countDown();
				if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test synchronization timed out.");
			}
			return result;
		}).when(repository).isDeletionPending(any(byte[].class));
		try (var pool = Executors.newSingleThreadExecutor()) {
			var login = pool.submit(() -> send("POST","/api/v1/auth/kakao",kakaoBody(),null,UUID.randomUUID()));
			try {
				assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
				jdbc.update("DELETE FROM `deviceSession` WHERE `userId`=?",bin(userId(auth)));
				jdbc.update("DELETE FROM `appUser` WHERE `id`=?",bin(userId(auth)));
				jdbc.update("""
					INSERT INTO `deletionJob` (`id`,`scope`,`accountSubjectHash`,`status`,`receiptHash`,`cutoffAt`,`dueAt`,`receiptExpiresAt`)
					VALUES (?,'ACCOUNT',?,'LOCAL_DELETED',?,?,?,?)
					""",bin(UUID.randomUUID()),crypto.hash("KAKAO_SUBJECT","12345"),crypto.hash("RECEIPT","race"),time(START),time(START.plusSeconds(86400)),time(START.plusSeconds(86400)));
			} finally { resume.countDown(); }
			assertThat(login.get(20,TimeUnit.SECONDS).text("code")).isEqualTo("ACCOUNT_DELETION_PENDING");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser`",Integer.class)).isZero();
		}
	}
	@Test void kakaoReplayRevalidatesProviderToken() throws Exception {
		var body = kakaoBody(); UUID key = UUID.randomUUID();
		var first = send("POST","/api/v1/auth/kakao",body,null,key);
		assertThat(send("POST","/api/v1/auth/kakao",body,null,key).body()).isEqualTo(first.body());
		when(kakao.verify(anyString())).thenThrow(new com.safecall.service.common.error.CustomException(com.safecall.service.common.error.ErrorCode.KAKAO_TOKEN_INVALID));
		assertThat(send("POST","/api/v1/auth/kakao",body,null,key).status()).isEqualTo(401);
	}
	@Test void logoutIsReplayableOnlyWithOriginalProofAndPreservesOtherDevice() throws Exception {
		var first = member(); var second = member(); UUID key = UUID.randomUUID();
		assertThat(send("POST","/api/v1/auth/logout",Map.of(),access(first),key).status()).isEqualTo(204);
		assertThat(send("POST","/api/v1/auth/logout",Map.of(),access(first),key).status()).isEqualTo(204);
		assertThat(send("POST","/api/v1/auth/logout",Map.of(),access(first),UUID.randomUUID()).status()).isEqualTo(401);
		assertThat(send("GET","/api/v1/session",null,access(first),null).status()).isEqualTo(401);
		assertThat(send("GET","/api/v1/session",null,access(second),null).status()).isEqualTo(200);
		assertThat(send("POST","/api/v1/auth/logout",Map.of(),access(guest()),UUID.randomUUID()).status()).isEqualTo(403);
	}
	@Test void concurrentIdenticalGuestRequestsIssueOneCredential() throws Exception {
		var body = guestBody(); UUID key = UUID.randomUUID();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			Callable<Result> work = () -> { gate.await(); return send("POST","/api/v1/auth/guest",body,null,key); };
			var first = pool.submit(work); var second = pool.submit(work); gate.countDown();
			var a = first.get(20,TimeUnit.SECONDS); var b = second.get(20,TimeUnit.SECONDS);
			assertThat(a.status()).isEqualTo(201); assertThat(b.status()).isEqualTo(201);
			assertThat(a.body()).isEqualTo(b.body());
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `sessionCredential`",Integer.class)).isEqualTo(1);
		}
	}
	@Test void concurrentRefreshWithSameKeyRotatesOnce() throws Exception {
		var auth = guest(); UUID key = UUID.randomUUID();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			Callable<Result> work = () -> { gate.await(); return refresh(auth.text("tokens","refreshToken"),key); };
			var first = pool.submit(work); var second = pool.submit(work); gate.countDown();
			var a = first.get(20,TimeUnit.SECONDS); var b = second.get(20,TimeUnit.SECONDS);
			assertThat(a.status()).isEqualTo(200); assertThat(b.status()).isEqualTo(200);
			assertThat(a.body()).isEqualTo(b.body());
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `sessionCredential`",Integer.class)).isEqualTo(2);
		}
	}
	@Test void unsupportedAcceptAndShortUuidStillReturnSafeJson() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
			.header("Accept", "text/html").GET().build();
		var result = http.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(result.statusCode()).isEqualTo(406);
		assertThat(mapper.readTree(result.body()).size()).isEqualTo(6);
		var shortKey = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/guest"))
			.header("Content-Type", "application/json").header("Idempotency-Key", "1-1-1-1-1")
			.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(guestBody()))).build();
		assertThat(http.send(shortKey, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
	}
	@Test void malformedAndFrameworkErrorsUseSixSafeFields() throws Exception {
		var missing = send("GET","/api/v1/session?secret=do-not-reflect",null,null,null);
		assertThat(missing.status()).isEqualTo(401);
		assertThat(missing.body().size()).isEqualTo(6);
		assertThat(missing.text("path")).isEqualTo("/api/v1/session");
		assertThat(missing.text("timestamp")).matches("[0-9-]{10}T[0-9:]{8}\\.[0-9]{6}");
		assertThat(send("GET","/does-not-exist",null,null,null).status()).isEqualTo(404);
		assertThat(send("GET","/api/v1/auth/guest",null,null,null).status()).isEqualTo(405);
		assertThat(raw("POST","/api/v1/auth/guest","{}",null,UUID.randomUUID(),"text/plain").status()).isEqualTo(415);
		for (String bad : List.of("{", "{} {}", "{\"installationId\":1}", "{\"unknown\":\"secret\"}", "{\"platform\":0}", "{\"platform\":\"ANDROID\",\"platform\":\"ANDROID\"}")) {
			var result = raw("POST","/api/v1/auth/guest",bad,null,UUID.randomUUID(),"application/json");
			assertThat(result.status()).as(bad).isEqualTo(400);
			assertThat(result.body().size()).isEqualTo(6);
			assertThat(result.body().toString()).doesNotContain("secret");
		}
		var invalid = send("POST","/api/v1/auth/guest",Map.of(),null,UUID.randomUUID());
		for (JsonNode error : invalid.body().path("errors")) assertThat(error.path("value").isNull()).isTrue();
	}
	private UUID seedCall(Result auth) {
		UUID release = UUID.randomUUID(), call = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO `promptRelease` (`id`,`version`,`safetyInstruction`,`demographicRules`,`guestInstruction`,`modelId`,`apiVersion`,`createdAt`)
			VALUES (?,1,'test','test','test','test','test',?)
			""",bin(release),time(START));
		jdbc.update("INSERT INTO `personaPrompt` (`releaseId`,`scenarioCode`,`counterpartCode`,`baseInstruction`,`voiceId`) VALUES (?,'FOLLOWED','FATHER','test','test')",bin(release));
		jdbc.update("""
			INSERT INTO `callSession` (`id`,`sessionId`,`clientCallId`,`releaseId`,`scenarioCode`,`counterpartCode`,`state`,`isDemographicApplied`,`isGenderAddressApplied`,`createdAt`,`lastHeartbeatAt`,`expiresAt`)
			VALUES (?,?,?,?,'FOLLOWED','FATHER','PREPARING',0,0,?,?,?)
			""",bin(call),bin(sessionId(auth)),bin(UUID.randomUUID()),bin(release),time(START),time(START),time(START.plusSeconds(540)));
		jdbc.update("""
			INSERT INTO `connectionGrant` (`callId`,`status`,`createdAt`,`tokenCipher`,`issuedAt`,`newSessionExpiresAt`,`expiresAt`)
			VALUES (?,'READY',?,X'010203',?,?,?)
			""",bin(call),time(START),time(START),time(START.plusSeconds(60)),time(START.plusSeconds(300)));
		return call;
	}
	@Test void logoutAtomicallyEndsCallAndRemovesGrantToken() throws Exception {
		var auth = member(); UUID call = seedCall(auth);
		assertThat(send("POST","/api/v1/auth/logout",Map.of(),access(auth),UUID.randomUUID()).status()).isEqualTo(204);
		assertThat(jdbc.queryForObject("SELECT `endReason` FROM `callSession` WHERE `id`=?",String.class,bin(call))).isEqualTo("LOGOUT");
		assertThat(jdbc.queryForObject("SELECT `tokenCipher` FROM `connectionGrant` WHERE `callId`=?",byte[].class,bin(call))).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `callEvent` WHERE `callId`=? AND `eventType`='ENDED'",Integer.class,bin(call))).isEqualTo(1);
	}
	@Test void failedLogoutRollsBackSessionAndCallChanges() throws Exception {
		var auth = member(); UUID call = seedCall(auth);
		jdbc.execute("CREATE TRIGGER authTestFailCall BEFORE UPDATE ON `callSession` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
		try {
			var result = send("POST","/api/v1/auth/logout",Map.of(),access(auth),UUID.randomUUID());
			assertThat(result.status()).isEqualTo(503);
			assertThat(result.text("code")).isEqualTo("LOGOUT_FAILED");
			assertThat(send("GET","/api/v1/session",null,access(auth),null).status()).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT `state` FROM `callSession` WHERE `id`=?",String.class,bin(call))).isEqualTo("PREPARING");
			assertThat(jdbc.queryForObject("SELECT `status` FROM `connectionGrant` WHERE `callId`=?",String.class,bin(call))).isEqualTo("READY");
		} finally { jdbc.execute("DROP TRIGGER authTestFailCall"); }
	}
	@Test void reusedRefreshAlsoClosesCall() throws Exception {
		var auth = guest(); UUID call = seedCall(auth);
		refresh(auth.text("tokens","refreshToken"),UUID.randomUUID());
		assertThat(refresh(auth.text("tokens","refreshToken"),UUID.randomUUID()).text("code")).isEqualTo("TOKEN_REUSED");
		assertThat(jdbc.queryForObject("SELECT `state` FROM `callSession` WHERE `id`=?",String.class,bin(call))).isEqualTo("ENDED");
		assertThat(jdbc.queryForObject("SELECT `status` FROM `connectionGrant` WHERE `callId`=?",String.class,bin(call))).isEqualTo("INVALIDATED");
	}
	@Test void concurrentRefreshWithDifferentKeysRevokesTheSession() throws Exception {
		var auth = guest();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			Callable<Result> work = () -> { gate.await(); return refresh(auth.text("tokens","refreshToken"),UUID.randomUUID()); };
			var first = pool.submit(work); var second = pool.submit(work); gate.countDown();
			var a = first.get(20,TimeUnit.SECONDS); var b = second.get(20,TimeUnit.SECONDS);
			assertThat(List.of(a.status(),b.status())).containsExactlyInAnyOrder(200,401);
			assertThat(jdbc.queryForObject("SELECT `status` FROM `deviceSession` WHERE `id`=?",String.class,bin(sessionId(auth)))).isEqualTo("REVOKED");
		}
	}
	@Test void concurrentKakaoSignupCreatesOneAccount() throws Exception {
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			Callable<Result> work = () -> { gate.await(); return send("POST","/api/v1/auth/kakao",kakaoBody(),null,UUID.randomUUID()); };
			var first = pool.submit(work); var second = pool.submit(work); gate.countDown();
			var a = first.get(20,TimeUnit.SECONDS); var b = second.get(20,TimeUnit.SECONDS);
			assertThat(a.status()).isEqualTo(200); assertThat(b.status()).isEqualTo(200);
			assertThat(userId(a)).isEqualTo(userId(b));
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `appUser`",Integer.class)).isEqualTo(1);
		}
	}
	@Test void guestCanUpgradeToKakaoOnlyWithCurrentSessionProof() throws Exception {
		var body = guestBody(); var auth = send("POST","/api/v1/auth/guest",body,null,UUID.randomUUID());
		var login = kakaoBody(); login.put("installationId",body.get("installationId"));
		assertThat(send("POST","/api/v1/auth/kakao",login,null,UUID.randomUUID()).status()).isEqualTo(401);
		var upgraded = send("POST","/api/v1/auth/kakao",login,access(auth),UUID.randomUUID());
		assertThat(upgraded.status()).isEqualTo(200);
		assertThat(upgraded.text("session","kind")).isEqualTo("KAKAO");
		assertThat(send("GET","/api/v1/session",null,access(auth),null).status()).isEqualTo(401);
	}
	@Test void initialReplayCannotReturnTokensAfterRotation() throws Exception {
		var body = guestBody(); UUID key = UUID.randomUUID();
		var auth = send("POST","/api/v1/auth/guest",body,null,key);
		refresh(auth.text("tokens","refreshToken"),UUID.randomUUID());
		assertThat(send("POST","/api/v1/auth/guest",body,null,key).text("code")).isEqualTo("AUTH_REPLAY_EXPIRED");
	}
	@Test void expiredInstallationSlotCanBeReusedWithoutOldProof() throws Exception {
		var body = guestBody(); send("POST","/api/v1/auth/guest",body,null,UUID.randomUUID());
		clock.advance(86401); body.put("bootstrapSecret",crypto.randomToken());
		assertThat(send("POST","/api/v1/auth/guest",body,null,UUID.randomUUID()).status()).isEqualTo(201);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `deviceSession` WHERE `status`='ACTIVE'",Integer.class)).isEqualTo(1);
	}
	@Test void withdrawalDuringOnboardingBlocksCompletion() throws Exception {
		var auth = member(); grantConsents(userId(auth));
		jdbc.update("UPDATE `deviceSession` SET `onboardingStep`='MESSAGE_TEST' WHERE `id`=?",bin(sessionId(auth)));
		jdbc.update("INSERT INTO `consentEvent` (`id`,`userId`,`documentCode`,`documentVersion`,`action`,`recordedAt`) VALUES (?,?,'AI_CALL',1,'WITHDRAWN',?)",
			bin(UUID.randomUUID()),bin(userId(auth)),time(START.plusSeconds(1)));
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","MESSAGE_TEST","expectedVersion",1,"testDecision","FINISH"),access(auth),UUID.randomUUID()).status()).isEqualTo(403);
	}
	@Test void authRateLimitUsesDatabaseAndReturnsRetryAfter() throws Exception {
		jdbc.update("""
			INSERT INTO `rateBucket` (`scopeKind`,`scopeHash`,`operation`,`windowStart`,`windowSeconds`,`usedCount`,`expiresAt`)
			VALUES ('IP',?,'AUTH',?,60,1000,?)
			""",crypto.hash("RATE_IP","127.0.0.1"),time(START),time(START.plusSeconds(120)));
		var result = send("POST","/api/v1/auth/guest",guestBody(),null,UUID.randomUUID());
		assertThat(result.status()).isEqualTo(429);
		assertThat(result.headers().firstValue("Retry-After")).contains("60");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `deviceSession`",Integer.class)).isZero();
	}
}

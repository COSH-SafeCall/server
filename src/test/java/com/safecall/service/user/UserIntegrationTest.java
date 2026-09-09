package com.safecall.service.user;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.safecall.service.auth.repository.AuthRepository.bin;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.kakao.KakaoClient;
import com.safecall.service.auth.kakao.KakaoClient.KakaoIdentity;
import com.safecall.service.user.service.ConsentCleanup;

@Tag("mysql")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserIntegrationTest {
	private static final Instant START=Instant.parse("2026-09-09T05:00:00Z");
	@LocalServerPort int port;
	@Autowired JdbcTemplate jdbc;
	@Autowired JsonMapper mapper;
	@Autowired ConsentCleanup cleanup;
	@MockitoBean KakaoClient kakao;
	@MockitoBean Clock clock;
	private final HttpClient http=HttpClient.newHttpClient();
	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		String url=System.getenv("AUTH_TEST_DB_URL");
		if (url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?!3306/)[0-9]+/safecall_auth_test_[0-9a-f]{16}\\?.*")) throw new IllegalStateException("Use the isolated MySQL test script.");
		r.add("spring.datasource.url",()->url); r.add("spring.datasource.username",()->"root"); r.add("spring.datasource.password",()->"");
		r.add("app.jwt.secret",()->"synthetic-signing-key-of-at-least-32-bytes");
		r.add("app.crypto.hmac-secret",()->Base64.getEncoder().encodeToString(new byte[32]));
		r.add("app.crypto.response-secret",()->Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		r.add("app.crypto.key-directory",()->System.getenv("AUTH_TEST_KEY_DIRECTORY"));
		r.add("app.oauth.kakao.app-id",()->123); r.add("app.auth.requests-per-minute",()->10000); r.add("app.auth.cleanup-delay-ms",()->3600000);
	}
	@BeforeEach void reset() {
		for (String table:List.of("deletionJob","deviceSession","appUser","deviceInstallation","rateBucket","serviceDocument","personaPrompt","promptRelease")) jdbc.update("DELETE FROM `"+table+"`");
		when(clock.instant()).thenReturn(START); when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(kakao.verify(anyString())).thenAnswer(inv -> new KakaoIdentity(inv.getArgument(0),"홍길동","MALE",LocalDate.of(2000,1,1),"01012345678"));
		for (String code:List.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING","HELP","SOS_GUIDE")) {
			boolean consent=!Set.of("HELP","SOS_GUIDE").contains(code);
			jdbc.update("INSERT INTO `serviceDocument` (`code`,`version`,`title`,`body`,`isConsent`,`isRequired`,`isCurrent`,`publishedAt`) VALUES (?,1,'합성 테스트 문서','운영 발행 금지',?,?,1,?)",code,consent,consent && !code.equals("LOCATION_PROCESSING"),com.safecall.service.auth.repository.AuthRepository.time(START));
		}
	}
	record Result(int status,JsonNode body,HttpHeaders headers) {
		String text(String... path) { JsonNode n=body; for(String p:path)n=n.path(p); return n.asString(); }
	}
	private Result send(String method,String path,Object body,String access,UUID key) throws Exception {
		var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Accept","application/json");
		if(access!=null)request.header("Authorization","Bearer "+access);
		if(key!=null)request.header("Idempotency-Key",key.toString());
		if(body!=null)request.header("Content-Type","application/json");
		request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
		var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
		return new Result(response.statusCode(),response.body().isBlank() ? null : mapper.readTree(response.body()),response.headers());
	}
	private Result login(String subject) throws Exception {
		var result=send("POST","/api/v1/auth/kakao",Map.of("installationId",UUID.randomUUID(),"platform","ANDROID","appVersion","1.0","osVersion","16","kakaoAccessToken",subject),null,UUID.randomUUID());
		assertThat(result.status()).isEqualTo(200); return result;
	}
	private String token(Result login) { return login.text("tokens","accessToken"); }
	private Map<String,Object> profile(long version) {
		Map<String,Object> body=new LinkedHashMap<>(); body.put("name","홍길동"); body.put("gender","MALE"); body.put("birthDate","2000-01-01"); body.put("phone","010-1234-5678"); body.put("expectedVersion",version); return body;
	}
	private Map<String,Object> contact(String phone) { return Map.of("name","김보호","relationship","가족","phone",phone); }
	private Result decide(String token,String code,String action,UUID key) throws Exception {
		return send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code",code,"version",1,"action",action))),token,key);
	}
	@Test void memberEndpointsRejectMissingAndGuestTokens() throws Exception {
		var guest=send("POST","/api/v1/auth/guest",Map.of("installationId",UUID.randomUUID(),"platform","ANDROID","appVersion","1","osVersion","16","bootstrapSecret",Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])),null,UUID.randomUUID());
		for(String path:List.of("profile","consents","emergency-contacts","settings")) {
			assertThat(send("GET","/api/v1/me/"+path,null,null,null).status()).isEqualTo(401);
			assertThat(send("GET","/api/v1/me/"+path,null,token(guest),null).text("code")).isEqualTo("LOGIN_REQUIRED");
		}
	}
	@Test void profilePreservesKakaoSourceThenMarksUserChangesAndEncrypts() throws Exception {
		String token=token(login("profile"));
		var updated=send("PUT","/api/v1/me/profile",profile(1),token,null);
		assertThat(updated.status()).isEqualTo(200); assertThat(updated.text("genderSource")).isEqualTo("KAKAO");
		assertThat(updated.text("phone")).isEqualTo("01012345678"); assertThat(updated.body().path("version").asLong()).isEqualTo(2);
		var changed=profile(2); changed.put("gender","FEMALE"); changed.put("birthDate",null);
		var result=send("PUT","/api/v1/me/profile",changed,token,null);
		assertThat(result.status()).isEqualTo(200); assertThat(result.text("genderSource")).isEqualTo("USER_CONFIRMED");
		assertThat(result.text("birthDateSource")).isEqualTo("UNKNOWN"); assertThat(result.body().path("birthDate").isNull()).isTrue();
		assertThat(jdbc.queryForObject("SELECT `nameCipher` FROM `appUser`",byte[].class)).isNotEqualTo("홍길동".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(send("PUT","/api/v1/me/profile",profile(1),token,null).text("code")).isEqualTo("VERSION_CONFLICT");
	}
	@Test void profileValidationAndUnknownFieldsDoNotWrite() throws Exception {
		String token=token(login("invalid"));
		for(String birth:List.of("2026-02-30","2100-01-01","2020-1-01")) {
			var body=profile(1); body.put("birthDate",birth);
			assertThat(send("PUT","/api/v1/me/profile",body,token,null).text("code")).isEqualTo("INVALID_BIRTH_DATE");
		}
		var body=profile(1); body.put("phone","0212345678"); assertThat(send("PUT","/api/v1/me/profile",body,token,null).text("code")).isEqualTo("INVALID_PHONE");
		body=profile(1); body.put("name","홍\n길동"); assertThat(send("PUT","/api/v1/me/profile",body,token,null).status()).isEqualTo(400);
		body=profile(1); body.remove("birthDate"); assertThat(send("PUT","/api/v1/me/profile",body,token,null).status()).isEqualTo(400);
		body=profile(1); body.put("genderSource","KAKAO"); assertThat(send("PUT","/api/v1/me/profile",body,token,null).status()).isEqualTo(400);
		assertThat(jdbc.queryForObject("SELECT `version` FROM `appUser`",Long.class)).isEqualTo(1);
	}
	@Test void contactsEnforceLimitsDuplicatesOwnershipAndDeleteReplay() throws Exception {
		String a=token(login("a")),b=token(login("b")); UUID key=UUID.randomUUID();
		var first=send("POST","/api/v1/me/emergency-contacts",contact("010-9876-5432"),a,key);
		assertThat(first.status()).isEqualTo(201); String id=first.text("id");
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01098765432"),a,key).text("id")).isEqualTo(id);
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01098765433"),a,key).text("code")).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01012345678"),a,UUID.randomUUID()).text("code")).isEqualTo("CONTACT_PHONE_DUPLICATE");
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01098765432"),a,UUID.randomUUID()).text("code")).isEqualTo("CONTACT_PHONE_DUPLICATE");
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01098765433"),a,UUID.randomUUID()).status()).isEqualTo(201);
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01098765434"),a,UUID.randomUUID()).text("code")).isEqualTo("CONTACT_LIMIT_REACHED");
		var own=profile(1); own.put("phone","01098765432"); assertThat(send("PUT","/api/v1/me/profile",own,a,null).text("code")).isEqualTo("CONTACT_PHONE_DUPLICATE");
		String path="/api/v1/me/emergency-contacts/"+id+"?expectedVersion=1";
		assertThat(send("DELETE",path,null,b,UUID.randomUUID()).text("code")).isEqualTo("CONTACT_NOT_FOUND");
		UUID deletion=UUID.randomUUID(); assertThat(send("DELETE",path,null,a,deletion).status()).isEqualTo(204);
		assertThat(send("DELETE",path,null,a,deletion).status()).isEqualTo(204);
		assertThat(send("DELETE",path+"0",null,a,deletion).text("code")).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(send("POST","/api/v1/me/emergency-contacts",contact("01098765435"),a,UUID.randomUUID()).body().path("slot").asInt()).isEqualTo(1);
	}
	@Test void concurrentContactCreationNeverExceedsTwo() throws Exception {
		String token=token(login("race"));
		try(var pool=Executors.newFixedThreadPool(3)) {
			var tasks=new ArrayList<Callable<Result>>();
			for(int n=0;n<3;n++) { String phone="0109999000"+n; tasks.add(()->send("POST","/api/v1/me/emergency-contacts",contact(phone),token,UUID.randomUUID())); }
			var results=pool.invokeAll(tasks); int created=0; for(var result:results)if(result.get().status()==201)created++;
			assertThat(created).isEqualTo(2); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `emergencyContact`",Integer.class)).isEqualTo(2);
		}
	}
	@Test void contactUpdateChecksOwnerAndVersion() throws Exception {
		String a=token(login("edit-a")),b=token(login("edit-b"));
		var created=send("POST","/api/v1/me/emergency-contacts",contact("01099998888"),a,UUID.randomUUID());
		String path="/api/v1/me/emergency-contacts/"+created.text("id");
		var change=Map.of("name","박보호","relationship","친구","phone","01088887777","expectedVersion",1);
		assertThat(send("PUT",path,change,b,null).text("code")).isEqualTo("CONTACT_NOT_FOUND");
		var edited=send("PUT",path,change,a,null);
		assertThat(edited.status()).isEqualTo(200); assertThat(edited.text("name")).isEqualTo("박보호");
		assertThat(edited.body().path("version").asLong()).isEqualTo(2);
		assertThat(send("PUT",path,change,a,null).text("code")).isEqualTo("VERSION_CONFLICT");
		assertThat(send("DELETE",path+"?expectedVersion=1",null,a,UUID.randomUUID()).text("code")).isEqualTo("VERSION_CONFLICT");
	}
	@Test void memberCompletesOnboardingUsingUserApis() throws Exception {
		String token=token(login("onboarding"));
		assertThat(send("PUT","/api/v1/me/profile",profile(1),token,null).status()).isEqualTo(200);
		for(var pair:List.of(Map.entry("PROFILE",1),Map.entry("CONTACTS",2))) {
			assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step",pair.getKey(),"expectedVersion",pair.getValue()),token,UUID.randomUUID()).status()).isEqualTo(200);
		}
		decide(token,"PRIVACY_PROCESSING","GRANTED",UUID.randomUUID()); decide(token,"AI_CALL","GRANTED",UUID.randomUUID());
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","CONSENTS","expectedVersion",3),token,UUID.randomUUID()).text("step")).isEqualTo("PERMISSIONS");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","PERMISSIONS","expectedVersion",4,"permissionReview",Map.of("microphone","GRANTED","location","DENIED")),token,UUID.randomUUID()).text("step")).isEqualTo("SOS_GUIDE");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","SOS_GUIDE","expectedVersion",5),token,UUID.randomUUID()).text("step")).isEqualTo("MESSAGE_TEST");
		assertThat(send("POST","/api/v1/onboarding/advance",Map.of("step","MESSAGE_TEST","expectedVersion",6,"testDecision","SKIP"),token,UUID.randomUUID()).text("step")).isEqualTo("COMPLETE");
		assertThat(jdbc.queryForObject("SELECT `status` FROM `appUser`",String.class)).isEqualTo("ACTIVE");
	}
	@Test void locationWithdrawalKeepsProfileAndContacts() throws Exception {
		String token=token(login("location"));
		send("POST","/api/v1/me/emergency-contacts",contact("01099998888"),token,UUID.randomUUID());
		assertThat(send("POST","/api/v1/me/consents/LOCATION_PROCESSING/withdraw",Map.of("version",1),token,UUID.randomUUID()).text("deletion","scope")).isEqualTo("LOCATION_DATA");
		cleanup.run();
		assertThat(send("GET","/api/v1/me/profile",null,token,null).text("gender")).isEqualTo("MALE");
		assertThat(send("GET","/api/v1/me/emergency-contacts",null,token,null).body().path("items").size()).isEqualTo(1);
	}
	@Test void settingsUseOptimisticVersions() throws Exception {
		String token=token(login("settings")); assertThat(send("GET","/api/v1/me/settings",null,token,null).text("incomingAlertMode")).isEqualTo("RINGTONE");
		var body=Map.of("incomingAlertMode","VIBRATE","expectedVersion",1);
		assertThat(send("PATCH","/api/v1/me/settings",body,token,null).text("incomingAlertMode")).isEqualTo("VIBRATE");
		assertThat(send("PATCH","/api/v1/me/settings",body,token,null).text("code")).isEqualTo("VERSION_CONFLICT");
	}
	@Test void publicDocumentsSupportEtagAndValidateCodes() throws Exception {
		var first=send("GET","/api/v1/documents?codes=HELP,SOS_GUIDE",null,null,null);
		assertThat(first.status()).isEqualTo(200); assertThat(first.body().path("items").size()).isEqualTo(2);
		String etag=first.headers().firstValue("ETag").orElseThrow();
		var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/documents?codes=SOS_GUIDE,HELP")).header("If-None-Match","W/"+etag).GET().build(),HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(304); assertThat(response.body()).isEmpty();
		assertThat(send("GET","/api/v1/documents?codes=BAD",null,null,null).text("code")).isEqualTo("DOCUMENT_CODE_INVALID");
	}
	@Test void consentBatchIsAtomicAndDeclineCannotBypassWithdrawal() throws Exception {
		String token=token(login("consents"));
		var batch=Map.of("decisions",List.of(Map.of("code","AI_CALL","version",1,"action","GRANTED"),Map.of("code","PRIVACY_PROCESSING","version",2,"action","GRANTED")));
		assertThat(send("POST","/api/v1/me/consents",batch,token,UUID.randomUUID()).text("code")).isEqualTo("CONSENT_VERSION_CHANGED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `consentEvent`",Integer.class)).isZero();
		UUID key=UUID.randomUUID(); assertThat(decide(token,"AI_CALL","GRANTED",key).status()).isEqualTo(200);
		assertThat(decide(token,"AI_CALL","GRANTED",key).status()).isEqualTo(200); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `consentEvent`",Integer.class)).isEqualTo(1);
		assertThat(decide(token,"AI_CALL","DECLINED",UUID.randomUUID()).text("code")).isEqualTo("WITHDRAWAL_REQUIRED");
		assertThat(decide(token,"HELP","GRANTED",UUID.randomUUID()).text("code")).isEqualTo("NOT_A_CONSENT_DOCUMENT");
	}
	@Test void aiWithdrawalBlocksDemographicsUntilCleanupAndReconsent() throws Exception {
		String token=token(login("withdraw")); decide(token,"AI_CALL","GRANTED",UUID.randomUUID());
		UUID key=UUID.randomUUID(); var result=send("POST","/api/v1/me/consents/AI_CALL/withdraw",Map.of("version",1),token,key);
		assertThat(result.status()).isEqualTo(200); assertThat(result.text("deletion","scope")).isEqualTo("AI_DATA");
		assertThat(send("POST","/api/v1/me/consents/AI_CALL/withdraw",Map.of("version",1),token,key).text("deletion","receiptToken")).isEqualTo(result.text("deletion","receiptToken"));
		assertThat(send("PUT","/api/v1/me/profile",profile(1),token,null).text("code")).isEqualTo("DATA_CLEANUP_PENDING");
		assertThat(decide(token,"AI_CALL","GRANTED",UUID.randomUUID()).text("code")).isEqualTo("DATA_CLEANUP_PENDING");
		cleanup.run();
		assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob`",String.class)).isEqualTo("COMPLETED");
		var after=send("GET","/api/v1/me/profile",null,token,null); assertThat(after.text("gender")).isEqualTo("UNKNOWN");
		assertThat(after.text("name")).isEqualTo("홍길동"); assertThat(after.body().path("birthDate").isNull()).isTrue();
		assertThat(send("PUT","/api/v1/me/profile",profile(2),token,null).text("code")).isEqualTo("CONSENT_REQUIRED");
		assertThat(decide(token,"AI_CALL","GRANTED",UUID.randomUUID()).status()).isEqualTo(200);
		assertThat(send("PUT","/api/v1/me/profile",profile(2),token,null).status()).isEqualTo(200);
	}
	@Test void privacyWithdrawalAllowsOnlyBoundedReceiptReplay() throws Exception {
		String token=token(login("privacy")); UUID key=UUID.randomUUID();
		var result=send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdraw",Map.of("version",1),token,key);
		assertThat(result.status()).isEqualTo(200); assertThat(result.text("deletion","scope")).isEqualTo("ACCOUNT");
		assertThat(send("GET","/api/v1/me/profile",null,token,null).text("code")).isEqualTo("ACCOUNT_DELETION_PENDING");
		assertThat(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdraw",Map.of("version",1),token,key).text("deletion","receiptToken")).isEqualTo(result.text("deletion","receiptToken"));
		when(clock.instant()).thenReturn(START.plusSeconds(61));
		assertThat(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdraw",Map.of("version",1),token,key).text("code")).isEqualTo("DELETION_RECEIPT_EXPIRED");
	}
	@Test void swaggerIncludesAllTwelveUserOperations() throws Exception {
		var result=send("GET","/v3/api-docs",null,null,null); var ids=new HashSet<String>();
		for(JsonNode path:result.body().path("paths"))for(JsonNode operation:path)if(operation.has("operationId"))ids.add(operation.path("operationId").asString());
		for(int n=1;n<=12;n++)assertThat(ids).contains("U%02d".formatted(n));
		var create=result.body().path("paths").path("/api/v1/me/emergency-contacts").path("post");
		assertThat(create.path("responses").has("201")).isTrue();
		assertThat(create.path("security").get(0).has("accessToken")).isTrue();
	}
}

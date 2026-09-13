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
	@Autowired AccountLocalCleanup accountsCleanup;@Autowired WebRetention retention;@MockitoSpyBean UserKeyStore userKeys;
	@MockitoBean KakaoCodeClient kakao;@MockitoBean GeminiClient gemini;
	@MockitoSpyBean AuthRepository authRepository;
	@Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
	@MockitoSpyBean com.safecall.service.message.service.MessagePolicy messagePolicy;
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
		r.add("app.oauth.kakao.client-id",()->"synthetic-client");r.add("app.oauth.kakao.app-id",()->123);
		r.add("app.gemini.connection-ttl-seconds",()->600);
		r.add("app.gemini.validated-model",()->"models/synthetic-live");r.add("app.gemini.validation-ref",()->"synthetic-test-only");r.add("app.gemini.model-max-seconds",()->600);
		r.add("app.auth.requests-per-minute",()->1000);r.add("app.auth.cleanup-delay-ms",()->3600000);r.add("app.call.worker-delay-ms",()->3600000);
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
		for(String table:List.of("deletionJob","webSession","appUser","rateBucket","serviceDocument","personaPrompt","promptRelease"))jdbc.update("DELETE FROM `"+table+"`");
		clock.value.set(START);
		when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("12345","홍길동","MALE",LocalDate.of(2000,1,1),"01012345678"));
		when(gemini.isConfigured()).thenReturn(true);when(gemini.issue(any())).thenReturn("auth_tokens/synthetic-only");
		for(String code:List.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING","HELP"))jdbc.update("INSERT INTO `serviceDocument` (`code`,`version`,`title`,`body`,`isConsent`,`isRequired`,`isCurrent`,`publishedAt`) VALUES (?,1,'synthetic','synthetic',?,?,1,?)",code,!code.equals("HELP"),Set.of("PRIVACY_PROCESSING","AI_CALL").contains(code),time(START.minusSeconds(60)));
		UUID release=UUID.randomUUID();jdbc.update("INSERT INTO `promptRelease` (`id`,`version`,`status`,`modelId`,`apiVersion`,`safetyInstruction`,`demographicRules`,`guestInstruction`,`validationRef`,`publishedAt`) VALUES (?,1,'PUBLISHED','models/synthetic-live','v1beta','safety','{age} {gender}','neutral','synthetic-test-only',?)",bin(release),time(START.minusSeconds(60)));
		for(String scenario:List.of("FOLLOWED","UNSAFE_TAXI","STRANGER_NEARBY","WALKING_ALONE"))for(String person:List.of("FATHER","MOTHER","FRIEND"))jdbc.update("INSERT INTO `personaPrompt` (`releaseId`,`scenarioCode`,`counterpartCode`,`baseInstruction`,`voiceId`) VALUES (?,?,?,'family conversation','Puck')",bin(release),scenario,person);
	}
	record Result(int status,JsonNode body,HttpHeaders headers){String text(String field){return body.path(field).asString();}}
	class Browser {String cookie,csrf;String page=crypto.randomToken();}
	Result raw(String method,String path,String body,Browser b,UUID key,String origin,String csrf,String page)throws Exception {
		var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Accept","application/json");
		if(origin!=null)req.header("Origin",origin);if(body!=null)req.header("Content-Type","application/json");
		if(b!=null&&b.cookie!=null)req.header("Cookie","__Host-safecall-session="+b.cookie);
		if(csrf!=null)req.header("X-CSRF-Token",csrf);if(page!=null)req.header("X-Call-Page-Key",page);if(key!=null)req.header("Idempotency-Key",key.toString());
		req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
		var response=http.send(req.build(),HttpResponse.BodyHandlers.ofString());
		return new Result(response.statusCode(),response.body().isBlank()?mapper.createObjectNode():mapper.readTree(response.body()),response.headers());
	}
	Result send(String method,String path,Object body,Browser b,UUID key)throws Exception{return raw(method,path,body==null?null:mapper.writeValueAsString(body),b,key,ORIGIN,b==null?null:b.csrf,b==null?null:b.page);}
	void status(Result r,int expected){assertThat(r.status()).as("response: %s",r.body()).isEqualTo(expected);}
	void code(Result r,int expected,String code){status(r,expected);assertThat(r.text("code")).isEqualTo(code);}
	void cookie(Browser b,Result r){r.headers().allValues("Set-Cookie").stream().filter(s->s.startsWith("__Host-safecall-session=")).findFirst().ifPresent(s->b.cookie=s.substring(s.indexOf('=')+1,s.indexOf(';')));}
	Browser bootstrap()throws Exception{Browser b=new Browser();var r=send("GET","/api/v1/auth/session",null,b,null);status(r,200);cookie(b,r);b.csrf=r.text("csrfToken");return b;}
	List<Map<String,Object>> decisions(boolean location){return List.of(Map.of("code","PRIVACY_PROCESSING","version",1,"action","GRANTED"),Map.of("code","AI_CALL","version",1,"action","GRANTED"),Map.of("code","LOCATION_PROCESSING","version",1,"action",location?"GRANTED":"DECLINED"));}
	String start(Browser b,String purpose,boolean location)throws Exception{
		var r=send("POST","/api/v1/auth/kakao/authorization",purpose.equals("LOGIN")?Map.of("purpose",purpose,"decisions",decisions(location)):Map.of("purpose",purpose),b,null);status(r,200);
		return Arrays.stream(URI.create(r.text("authorizationUrl")).getRawQuery().split("&")).filter(s->s.startsWith("state=")).findFirst().orElseThrow().substring(6);
	}
	Result callback(Browser b,String state)throws Exception{return send("GET","/api/v1/auth/kakao/callback?state="+state+"&code=synthetic",null,b,null);}
	void login(Browser b,String purpose,boolean location)throws Exception{var r=callback(b,start(b,purpose,location));status(r,303);cookie(b,r);var session=send("GET","/api/v1/auth/session",null,b,null);b.csrf=session.text("csrfToken");}
	Browser member()throws Exception{Browser b=bootstrap();login(b,"LOGIN",false);return b;}
	void advance(Browser b,String step)throws Exception{var state=send("GET","/api/v1/onboarding",null,b,null);Map<String,Object> body=new HashMap<>(Map.of("step",step,"expectedVersion",state.body().path("version").asLong()));
		if(Set.of("PERMISSIONS","SOS_GUIDE").contains(step))body.put("isNoticeReviewed",true);if(step.equals("MESSAGE_TEST"))body.put("testDecision","SKIP");status(send("POST","/api/v1/onboarding/advance",body,b,UUID.randomUUID()),200);}
	Browser guest()throws Exception{Browser b=bootstrap();var r=send("POST","/api/v1/auth/guest",Map.of(),b,null);status(r,200);cookie(b,r);b.csrf=r.text("csrfToken");advance(b,"PERMISSIONS");advance(b,"SOS_GUIDE");return b;}
	Map<String,Object> profile(long version){Map<String,Object> body=new HashMap<>();body.put("name","홍길동");body.put("phone","+82 10-1234-5678");body.put("gender",null);body.put("birthDate",null);body.put("isConfirmed",true);body.put("expectedVersion",version);return body;}
	void complete(Browser b)throws Exception{var p=send("GET","/api/v1/me/profile",null,b,null);status(send("PATCH","/api/v1/me/profile",profile(p.body().path("version").asLong()),b,UUID.randomUUID()),200);for(String step:List.of("PROFILE","CONTACTS","PERMISSIONS","SOS_GUIDE","MESSAGE_TEST"))advance(b,step);}
	UUID sessionId(Browser b){return authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).id();}
	UUID userId(Browser b){return authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).userId();}
	Map<String,Object> createBody(){return Map.of("clientCallId",UUID.randomUUID(),"startMode","QUICK","scenarioCode","FOLLOWED","counterpartCode","FATHER","microphonePermission","GRANTED");}
	UUID create(Browser b)throws Exception{var r=send("POST","/api/v1/calls",createBody(),b,UUID.randomUUID());status(r,202);return UUID.fromString(r.text("id"));}
	Result connection(Browser b,UUID id)throws Exception{return send("GET","/api/v1/calls/"+id+"/connection",null,b,null);}
	Map<String,Object> eventBody(Browser b,UUID id,String type,String grant)throws Exception{var c=send("GET","/api/v1/calls/"+id,null,b,null);Map<String,Object> body=new HashMap<>(Map.of("eventId",UUID.randomUUID(),"type",type,"occurredAt",clock.instant().toString(),"expectedVersion",c.body().path("version").asLong()));if(grant!=null)body.put("grantId",grant);return body;}
	Result event(Browser b,UUID id,String type,String grant)throws Exception{return send("POST","/api/v1/calls/"+id+"/events",eventBody(b,id,type,grant),b,UUID.randomUUID());}
	String active(Browser b,UUID id)throws Exception{worker.runOne(id);var g=connection(b,id);status(g,200);String grant=g.text("grantId");status(event(b,id,"CONNECTED",grant),200);status(event(b,id,"RINGING_SHOWN",null),200);status(event(b,id,"ANSWERED",null),200);return grant;}
	Result end(Browser b,UUID id)throws Exception{return send("POST","/api/v1/calls/"+id+"/end",Map.of("reason","TAB_HIDDEN","occurredAt",clock.instant().toString()),b,UUID.randomUUID());}
	Result renew(Browser b,UUID id,String grant,UUID key)throws Exception{return send("POST","/api/v1/calls/"+id+"/connection-renewals",Map.of("previousGrantId",grant,"reason","GO_AWAY","isResumable",true),b,key);}
	long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class);}

	@Test void anonymousCookieAndStableCsrfArePrivate()throws Exception{
		var first=send("GET","/api/v1/auth/session",null,null,null);status(first,200);String cookie=first.headers().firstValue("Set-Cookie").orElseThrow();
		assertThat(cookie).contains("__Host-safecall-session=","Secure","HttpOnly","SameSite=Lax","Path=/").doesNotContain("Domain");
		assertThat(first.body().properties()).hasSize(6);assertThat(first.text("kind")).isEqualTo("ANONYMOUS");
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
	@Test void oauthStoresConsentOnlyBeforeProviderCall()throws Exception{
		Browser b=bootstrap();String state=start(b,"LOGIN",false);assertThat(count("oauthConsent")).isEqualTo(3);assertThat(count("appUser")).isZero();verifyNoInteractions(kakao);
		var r=callback(b,state);status(r,303);assertThat(r.headers().firstValue("Location")).contains("/onboarding/profile");assertThat(count("appUser")).isEqualTo(1);assertThat(count("consentEvent")).isEqualTo(3);
		status(callback(b,state),303);verify(kakao,times(1)).exchange(anyString(),anyString());
	}
	@Test void oauthRejectsMissingDuplicateOrOutdatedConsent()throws Exception{Browser b=bootstrap();status(send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","LOGIN"),b,null),422);
		var duplicate=List.of(decisions(false).getFirst(),decisions(false).getFirst(),decisions(false).getLast());code(send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","LOGIN","decisions",duplicate),b,null),422,"INVALID_CONSENT");assertThat(count("oauthAttempt")).isZero();}
	@Test void oauthCallbackRequiresStartingCookieAndUnexpiredState()throws Exception{Browser b=bootstrap(),other=bootstrap();String state=start(b,"LOGIN",false);assertThat(callback(other,state).headers().firstValue("Location")).contains("/login?reason=oauth_failed");clock.advance(601);assertThat(callback(b,state).headers().firstValue("Location")).contains("/login?reason=oauth_failed");verifyNoInteractions(kakao);}
	@Test void callbackConsentRecheckHappensBeforeCodeExchange()throws Exception{Browser b=bootstrap();String state=start(b,"LOGIN",false);jdbc.update("UPDATE `serviceDocument` SET `isCurrent`=0 WHERE `code`='AI_CALL'");status(callback(b,state),303);verifyNoInteractions(kakao);assertThat(count("appUser")).isZero();}
	@Test void callbackChecksConsentAgainAfterExternalCall()throws Exception{Browser b=bootstrap();String state=start(b,"LOGIN",false);when(kakao.exchange(anyString(),anyString())).thenAnswer(i->{jdbc.update("UPDATE `serviceDocument` SET `isCurrent`=0 WHERE `code`='AI_CALL'");return new KakaoClient.KakaoIdentity("12345","홍길동",null,null,"01012345678");});assertThat(callback(b,state).headers().firstValue("Location")).contains("/login?reason=oauth_failed");assertThat(count("appUser")).isZero();}
	@Test void explicitReauthPreservesStepAndMarksSensitiveSession()throws Exception{Browser b=member();String old=b.cookie;login(b,"REAUTH",false);assertThat(b.cookie).isNotEqualTo(old);assertThat(authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).sensitiveVerifiedAt()).isEqualTo(START);assertThat(send("GET","/api/v1/onboarding",null,b,null).text("step")).isEqualTo("PROFILE");}
	@Test void reauthCannotSwitchAccountsOrAcceptDecisions()throws Exception{Browser b=member();status(send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","REAUTH","decisions",decisions(false)),b,null),400);String state=start(b,"REAUTH",false);when(kakao.exchange(anyString(),anyString())).thenReturn(new KakaoClient.KakaoIdentity("99999",null,null,null,null));code(callback(b,state),403,"REAUTH_ACCOUNT_MISMATCH");assertThat(count("appUser")).isEqualTo(1);}
	@Test void logoutIsAtomicRepeatableAndOtherBrowserStaysValid()throws Exception{Browser b=member(),other=member();status(send("POST","/api/v1/auth/logout",Map.of(),b,null),204);status(send("POST","/api/v1/auth/logout",Map.of(),b,null),204);status(send("GET","/api/v1/me/profile",null,other,null),200);status(send("POST","/api/v1/auth/logout",Map.of(),null,null),204);}
	@Test void logoutDatabaseFailureKeepsSession()throws Exception{Browser b=guest();doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic")).when(authRepository).endSession(any(),any(),anyString(),anyString(),any());code(send("POST","/api/v1/auth/logout",Map.of(),b,null),503,"LOGOUT_FAILED");assertThat(authRepository.byCookie(crypto.hash("WEB_SESSION",b.cookie)).status()).isEqualTo("ACTIVE");}
	@Test void onboardingUsesNoticesAndSkipsReservedConsentStep()throws Exception{Browser b=member();code(send("POST","/api/v1/onboarding/advance",Map.of("step","CONSENTS","expectedVersion",1),b,UUID.randomUUID()),422,"VALIDATION_FAILED");complete(b);assertThat(send("GET","/api/v1/onboarding",null,b,null).text("step")).isEqualTo("COMPLETE");}
	@Test void profileRequiresPrivacyAndDemographicsRequireAi()throws Exception{Browser b=member();jdbc.update("DELETE FROM `consentEvent` WHERE `documentCode`='PRIVACY_PROCESSING'");code(send("GET","/api/v1/me/profile",null,b,null),403,"CONSENT_REQUIRED");}
	@Test void profilePatchNormalizesAndReplaysBeforeVersionCheck()throws Exception{Browser b=member();UUID key=UUID.randomUUID();var body=profile(1);var r=send("PATCH","/api/v1/me/profile",body,b,key);status(r,200);assertThat(r.text("phone")).isEqualTo("01012345678");assertThat(r.body().path("gender").isNull()).isTrue();assertThat(r.body().has("userId")).isFalse();assertThat(r.body().has("profileConfirmedAt")).isTrue();status(send("PATCH","/api/v1/me/profile",body,b,key),200);code(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),409,"VERSION_CONFLICT");}
	@Test void profileRejectsMissingNullableFieldsUnknownFieldsAndInvalidDate()throws Exception{Browser b=member();var body=profile(1);body.remove("gender");status(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),400);body=profile(1);body.put("birthDate","2027-01-01");code(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),422,"INVALID_BIRTH_DATE");body=profile(1);body.put("userId",UUID.randomUUID());status(send("PATCH","/api/v1/me/profile",body,b,UUID.randomUUID()),400);}
	@Test void documentsAreSingleVersionedResourcesWithEtag()throws Exception{var r=send("GET","/api/v1/documents/HELP",null,null,null);status(r,200);assertThat(r.body().has("purpose")).isFalse();String etag=r.headers().firstValue("ETag").orElseThrow();var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/documents/HELP")).header("If-None-Match",etag).GET().build();assertThat(http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(304);status(send("GET","/api/v1/documents/HELP?version=99",null,null,null),404);code(send("GET","/api/v1/documents/SOS_GUIDE",null,null,null),503,"DOCUMENT_NOT_READY");}
	@Test void consentsUseCurrentVersionAndEffectiveFields()throws Exception{Browser b=member();var r=send("GET","/api/v1/me/consents",null,b,null);status(r,200);var item=r.body().path("items").get(0);assertThat(item.has("currentVersion")).isTrue();assertThat(item.has("isEffective")).isTrue();assertThat(item.has("isValid")).isFalse();}
	@Test void consentRejectsOldVersionAndRequiresWithdrawalForEffectiveGrant()throws Exception{Browser b=member();code(send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code","AI_CALL","version",99,"action","GRANTED"))),b,UUID.randomUUID()),422,"INVALID_CONSENT");code(send("POST","/api/v1/me/consents",Map.of("decisions",List.of(Map.of("code","AI_CALL","version",1,"action","DECLINED"))),b,UUID.randomUUID()),409,"WITHDRAWAL_REQUIRED");}
	@Test void sensitiveWithdrawalNeedsRecentExplicitReauthAndReturnsOnlyReceiptCookie()throws Exception{Browser b=member();code(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");login(b,"REAUTH",false);var r=send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID());status(r,202);assertThat(r.text("scope")).isEqualTo("ACCOUNT");assertThat(r.body().has("receiptToken")).isFalse();assertThat(r.headers().firstValue("Set-Cookie").orElseThrow()).contains("__Host-safecall-deletion=","HttpOnly");}
	@Test void reauthExpiresAfterFiveMinutes()throws Exception{Browser b=member();login(b,"REAUTH",false);clock.advance(300);code(send("POST","/api/v1/me/consents/PRIVACY_PROCESSING/withdrawal",Map.of(),b,UUID.randomUUID()),403,"REAUTHENTICATION_REQUIRED");}
	@Test void aiWithdrawalCleansDataAtomicallyAndRetainsContactAndIdentity()throws Exception{Browser b=member();complete(b);UUID call=create(b);worker.runOne(call);var r=send("POST","/api/v1/me/consents/AI_CALL/withdrawal",Map.of(),b,UUID.randomUUID());status(r,202);assertThat(send("GET","/api/v1/calls/"+call,null,b,null).text("endReason")).isEqualTo("CONSENT_WITHDRAWN");cleanup.run();assertThat(count("callSession")).isZero();var user=authRepository.user(userId(b),false);assertThat(user.genderCipher()).isNull();assertThat(user.nameCipher()).isNotNull();assertThat(jdbc.queryForObject("SELECT `status` FROM `deletionJob`",String.class)).isEqualTo("COMPLETED");}
	@Test void oauthLocationDeclineUsesWithdrawalCleanup()throws Exception{Browser b=bootstrap();login(b,"LOGIN",true);login(b,"LOGIN",false);assertThat(jdbc.queryForObject("SELECT `action` FROM `consentEvent` WHERE `documentCode`='LOCATION_PROCESSING' ORDER BY `recordedAt` DESC LIMIT 1",String.class)).isEqualTo("WITHDRAWN");assertThat(jdbc.queryForObject("SELECT `scope` FROM `deletionJob`",String.class)).isEqualTo("LOCATION_DATA");}
	@Test void contactsEnforceSlotsDuplicatesAndTargetScopedIdempotency()throws Exception{Browser b=member();UUID key=UUID.randomUUID();List<String> ids=new ArrayList<>();for(String phone:List.of("01011112222","01033334444")){var r=send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone",phone),b,UUID.randomUUID());status(r,201);assertThat(r.headers().firstValue("Location")).isPresent();ids.add(r.text("id"));}for(String id:ids)status(send("DELETE","/api/v1/me/emergency-contacts/"+id,Map.of("expectedVersion",1),b,key),204);status(send("DELETE","/api/v1/me/emergency-contacts/"+ids.getFirst(),Map.of("expectedVersion",1),b,key),204);status(send("DELETE","/api/v1/me/emergency-contacts/"+ids.getFirst(),Map.of("expectedVersion",1),b,UUID.randomUUID()),404);assertThat(count("emergencyContact")).isZero();}
	@Test void settingsRequireIdempotencyAndExcludeVibration()throws Exception{Browser b=member();UUID key=UUID.randomUUID();var body=Map.of("incomingAlertMode","SILENT","expectedVersion",1);status(send("PATCH","/api/v1/me/settings",body,b,key),200);status(send("PATCH","/api/v1/me/settings",body,b,key),200);status(send("PATCH","/api/v1/me/settings",body,b,null),400);status(send("PATCH","/api/v1/me/settings",Map.of("incomingAlertMode","VIBRATE","expectedVersion",2),b,UUID.randomUUID()),422);}
	@Test void homeReflectsActiveCallAndCatalogVersion()throws Exception{Browser b=member();complete(b);status(send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone","01011112222"),b,UUID.randomUUID()),201);assertThat(send("GET","/api/v1/home",null,b,null).body().path("isMessageComposeEligible").asBoolean()).isTrue();create(b);assertThat(send("GET","/api/v1/home",null,b,null).body().path("messageBlockReasons").toString()).contains("CALL_ALREADY_OPEN");assertThat(send("GET","/api/v1/call-options",null,b,null).body().path("catalogVersion").asInt()).isEqualTo(3);}
	@Test void callCreationSnapshotsPolicyAndRequiresPageOwnership()throws Exception{Browser b=guest();UUID id=create(b);var r=send("GET","/api/v1/calls/"+id,null,b,null);status(r,200);assertThat(r.text("expiresAt")).isEqualTo(START.plusSeconds(600).toString());assertThat(r.text("leaseExpiresAt")).isEqualTo(START.plusSeconds(30).toString());assertThat(r.body().path("maxResumeAttempts").asInt()).isEqualTo(1);String page=b.page;b.page=crypto.randomToken();status(send("GET","/api/v1/calls/"+id,null,b,null),404);b.page=page;Browser other=guest();status(send("GET","/api/v1/calls/"+id,null,other,null),404);}
	@Test void sameClientCallIsDeduplicatedButCannotMovePages()throws Exception{Browser b=guest();var body=createBody();var first=send("POST","/api/v1/calls",body,b,UUID.randomUUID());status(first,202);var next=send("POST","/api/v1/calls",body,b,UUID.randomUUID());status(next,202);assertThat(next.text("id")).isEqualTo(first.text("id"));b.page=crypto.randomToken();status(send("POST","/api/v1/calls",body,b,UUID.randomUUID()),404);assertThat(count("connectionGrant")).isEqualTo(1);}
	@Test void duplicateCallKeysConflictOnDifferentBody()throws Exception{Browser b=guest();UUID key=UUID.randomUUID();status(send("POST","/api/v1/calls",createBody(),b,key),202);code(send("POST","/api/v1/calls",createBody(),b,key),409,"IDEMPOTENCY_CONFLICT");}
	@Test void initialGrantIsIssuedOnceAndTokenKeyRemovedOnConnected()throws Exception{Browser b=guest();UUID id=create(b);status(connection(b,id),202);worker.runOne(id);worker.runOne(id);verify(gemini,times(1)).issue(any());var g=connection(b,id);status(g,200);assertThat(g.body().path("sessionResumption").path("isEnabled").asBoolean()).isTrue();String ref=jdbc.queryForObject("SELECT `keyRef` FROM `connectionGrant`",String.class);assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isTrue();status(event(b,id,"CONNECTED",g.text("grantId")),200);assertThat(jdbc.queryForObject("SELECT `tokenCipher` FROM `connectionGrant`",byte[].class)).isNull();assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("AUTH_TEST_KEY_DIRECTORY"),ref))).isFalse();code(connection(b,id),409,"CONNECTION_ALREADY_USED");}
	@Test void invalidTransitionAndEventReplayDoNotCorruptVersion()throws Exception{Browser b=guest();UUID id=create(b);code(event(b,id,"ANSWERED",null),409,"CALL_TRANSITION_INVALID");worker.runOne(id);String grant=connection(b,id).text("grantId");var body=eventBody(b,id,"CONNECTED",grant);UUID key=UUID.randomUUID();status(send("POST","/api/v1/calls/"+id+"/events",body,b,key),200);status(send("POST","/api/v1/calls/"+id+"/events",body,b,UUID.randomUUID()),200);assertThat(send("GET","/api/v1/calls/"+id,null,b,null).body().path("version").asLong()).isEqualTo(3);body.put("type","RESUMED");code(send("POST","/api/v1/calls/"+id+"/events",body,b,key),409,"IDEMPOTENCY_CONFLICT");}
	@Test void heartbeatCannotReviveExpiredLease()throws Exception{Browser b=guest();UUID id=create(b);active(b,id);clock.advance(30);code(send("POST","/api/v1/calls/"+id+"/heartbeat",Map.of(),b,null),409,"CALL_TERMINAL");assertThat(send("GET","/api/v1/calls/"+id,null,b,null).text("endReason")).isEqualTo("SESSION_EXPIRED");}
	@Test void heartbeatUpdatesLeaseWithoutExtendingDurationOrVersion()throws Exception{Browser b=guest();UUID id=create(b);active(b,id);var before=send("GET","/api/v1/calls/"+id,null,b,null);clock.advance(5);var beat=send("POST","/api/v1/calls/"+id+"/heartbeat",Map.of(),b,null);status(beat,200);assertThat(beat.text("leaseExpiresAt")).isEqualTo(START.plusSeconds(35).toString());var after=send("GET","/api/v1/calls/"+id,null,b,null);assertThat(after.text("expiresAt")).isEqualTo(before.text("expiresAt"));assertThat(after.body().path("version")).isEqualTo(before.body().path("version"));}
	@Test void callEndIsTerminalAndUsesWebReason()throws Exception{Browser b=guest();UUID id=create(b);worker.runOne(id);status(end(b,id),200);var again=end(b,id);status(again,200);assertThat(again.text("endReason")).isEqualTo("TAB_HIDDEN");assertThat(jdbc.queryForObject("SELECT `status` FROM `connectionGrant`",String.class)).isEqualTo("INVALIDATED");}
	@Test void resumeCreatesOneGenerationKeepsActiveAndRejectsStaleCallbacks()throws Exception{Browser b=guest();UUID id=create(b);String initial=active(b,id);UUID key=UUID.randomUUID();var renewal=renew(b,id,initial,key);status(renewal,202);assertThat(renew(b,id,initial,key).text("grantId")).isEqualTo(renewal.text("grantId"));code(renew(b,id,initial,UUID.randomUUID()),409,"RENEWAL_ALREADY_REQUESTED");worker.runOne(id);var resumed=connection(b,id);status(resumed,200);assertThat(resumed.text("purpose")).isEqualTo("RESUME");code(event(b,id,"CONNECTED",initial),409,"STALE_CONNECTION_GENERATION");status(event(b,id,"RESUMED",resumed.text("grantId")),200);code(renew(b,id,resumed.text("grantId"),UUID.randomUUID()),409,"RESUME_BUDGET_EXHAUSTED");assertThat(count("connectionGrant")).isEqualTo(2);assertThat(send("GET","/api/v1/calls/"+id,null,b,null).text("state")).isEqualTo("ACTIVE");}
	@Test void resumeDoesNotRecomposeChangedProfileOrAcceptHandle()throws Exception{Browser b=guest();UUID id=create(b);String initial=active(b,id);status(send("POST","/api/v1/calls/"+id+"/connection-renewals",Map.of("previousGrantId",initial,"reason","GO_AWAY","isResumable",true,"handle","secret"),b,UUID.randomUUID()),400);status(renew(b,id,initial,UUID.randomUUID()),202);var request=calls.claim(id);assertThat(request.instruction()).isNull();assertThat(request.purpose()).isEqualTo("RESUME");}
	@Test void resumeIssueUnknownClosesAsResumptionFailed()throws Exception{Browser b=guest();UUID id=create(b);String initial=active(b,id);status(renew(b,id,initial,UUID.randomUUID()),202);when(gemini.issue(any())).thenThrow(new GeminiClient.IssueException(true));worker.runOne(id);var r=send("GET","/api/v1/calls/"+id,null,b,null);assertThat(r.text("state")).isEqualTo("FAILED");assertThat(r.text("endReason")).isEqualTo("RESUMPTION_FAILED");code(connection(b,id),503,"CONNECTION_ISSUE_UNKNOWN");}
	@Test void stalledIssueTimesOutAtTenSecondsAndNeverReissues()throws Exception{Browser b=guest();UUID id=create(b);assertThat(calls.claim(id)).isNotNull();clock.advance(10);worker.runOne(id);code(connection(b,id),503,"CONNECTION_ISSUE_UNKNOWN");verify(gemini,never()).issue(any());}
	@Test void workerDiscardsLateResponseAfterEnd()throws Exception{Browser b=guest();UUID id=create(b);var request=calls.claim(id);status(end(b,id),200);calls.finish(id,request,"auth_tokens/late",null);assertThat(jdbc.queryForObject("SELECT `tokenCipher` FROM `connectionGrant`",byte[].class)).isNull();assertThat(connection(b,id).status()).isEqualTo(409);}
	@Test void concurrentSameCreateIsSingleCall()throws Exception{Browser b=guest();var body=createBody();UUID key=UUID.randomUUID();try(var executor=Executors.newFixedThreadPool(2)){var a=executor.submit(()->send("POST","/api/v1/calls",body,b,key));var c=executor.submit(()->send("POST","/api/v1/calls",body,b,key));status(a.get(),202);status(c.get(),202);}assertThat(count("callSession")).isEqualTo(1);}
	@Test void concurrentContactCreatesEnforceTwoSlots()throws Exception{Browser b=member();try(var executor=Executors.newFixedThreadPool(3)){List<Future<Result>> results=new ArrayList<>();for(String phone:List.of("01011112222","01033334444","01055556666"))results.add(executor.submit(()->send("POST","/api/v1/me/emergency-contacts",Map.of("name","보호자","relationship","가족","phone",phone),b,UUID.randomUUID())));List<Integer> statuses=new ArrayList<>();for(var f:results)statuses.add(f.get().status());assertThat(statuses).containsExactlyInAnyOrder(201,201,409);}assertThat(count("emergencyContact")).isEqualTo(2);}
	@Test void unknownJsonAndDuplicateKeysAreRejectedWithoutLeakingValues()throws Exception{Browser b=bootstrap();var r=raw("POST","/api/v1/auth/guest","{\"secret\":\"sensitive\",\"secret\":\"sensitive\"}",b,null,ORIGIN,b.csrf,null);status(r,400);assertThat(r.body().toString()).doesNotContain("sensitive");assertThat(r.text("timestamp")).endsWith("Z");}
	@Test void openApiExposesOnlyImplementedWebOperations()throws Exception{var r=send("GET","/v3/api-docs",null,null,null);status(r,200);var paths=r.body().path("paths");assertThat(paths.has("/api/v1/auth/refresh")).isFalse();assertThat(paths.has("/api/v1/calls/{callId}/connection-renewals")).isTrue();assertThat(paths.path("/api/v1/me/profile").has("patch")).isTrue();assertThat(r.body().path("components").path("securitySchemes").path("webSession").path("in").asString()).isEqualTo("cookie");}

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
	@Test void requiredConsentDeclineCannotStartOAuth()throws Exception{
		Browser b=bootstrap();var choices=new ArrayList<>(decisions(false));choices.set(0,Map.of("code","PRIVACY_PROCESSING","version",1,"action","DECLINED"));code(send("POST","/api/v1/auth/kakao/authorization",Map.of("purpose","LOGIN","decisions",choices),b,null),403,"CONSENT_REQUIRED");assertThat(count("oauthAttempt")).isZero();
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
	@Test void retiredPromptReleaseStaysPinnedDuringResume()throws Exception{
		Browser b=guest();UUID id=create(b);String initial=active(b,id);jdbc.update("UPDATE `promptRelease` SET `status`='RETIRED'");status(renew(b,id,initial,UUID.randomUUID()),202);worker.runOne(id);status(connection(b,id),200);assertThat(connection(b,id).text("model")).isEqualTo("models/synthetic-live");
	}
	@Test void parallelRenewalsConsumeAtMostOneBudget()throws Exception{
		Browser b=guest();UUID id=create(b);String initial=active(b,id);try(var executor=Executors.newFixedThreadPool(2)){var a=executor.submit(()->renew(b,id,initial,UUID.randomUUID()));var c=executor.submit(()->renew(b,id,initial,UUID.randomUUID()));assertThat(List.of(a.get().status(),c.get().status())).containsExactlyInAnyOrder(202,409);}assertThat(count("connectionGrant")).isEqualTo(2);
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
		assertThatThrownBy(()->userKeys.read(keyRef)).isInstanceOf(RuntimeException.class);
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
	@Test void abandonedOnboardingQueuesAccountDeletionAfter24Hours()throws Exception{
		member();clock.advance(86399);accountsCleanup.run();assertThat(count("deletionJob")).isZero();clock.advance(1);accountsCleanup.run();
		assertThat(jdbc.queryForObject("SELECT `status` FROM `appUser`",String.class)).isEqualTo("DELETION_PENDING");clock.advance(60);accountsCleanup.run();assertThat(count("appUser")).isZero();
	}
	@Test void retentionRemovesExpiredGuestCallsAndKeysButKeepsActiveMember()throws Exception{
		Browser member=member();complete(member);Browser guest=guest();UUID call=create(guest);worker.runOne(call);
		String ref=jdbc.queryForObject("SELECT `keyRef` FROM `connectionGrant` WHERE `callId`=?",String.class,bin(call));
		clock.advance(90000);retention.run();assertThat(count("callSession")).isZero();assertThat(count("appUser")).isEqualTo(1);assertThat(count("webSession")).isEqualTo(1);assertThatThrownBy(()->userKeys.read(ref)).isInstanceOf(RuntimeException.class);
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
	@Test void renewalReplayCannotReviveAnEndedCall()throws Exception{
		Browser b=guest();UUID call=create(b);String grant=active(b,call);UUID key=UUID.randomUUID();status(renew(b,call,grant,key),202);end(b,call);code(renew(b,call,grant,key),409,"CALL_TERMINAL");
	}
	@Test void finalSchemaHasExpectedTablesColumnsAndConstraints(){
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isEqualTo(19);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()",Integer.class)).isEqualTo(189);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE()",Integer.class)).isEqualTo(171);
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
	@Test void composerTestModeAllowsOnlyMessageTestAndCompleteSteps() throws Exception {
		Browser b=member(); guardian(b,"보호자","01087654321");
		status(send("PATCH","/api/v1/me/profile",profile(1),b,UUID.randomUUID()),200);
		for (String step:List.of("PROFILE","CONTACTS","PERMISSIONS","SOS_GUIDE")) {
			code(composer(b,"?mode=TEST"),403,"ONBOARDING_REQUIRED"); advance(b,step);
		}
		code(composer(b,""),403,"ONBOARDING_REQUIRED");
		var result=composer(b,"?mode=TEST"); status(result,200);
		assertThat(result.text("baseBody")).isEqualTo("[테스트] 홍길동(010-xxxx-5678)의 SafeCall 안심 메시지입니다.");
		assertThat(result.text("mode")).isEqualTo("TEST");
		advance(b,"MESSAGE_TEST"); status(composer(b,"?mode=TEST"),200); status(composer(b,"?mode=SAFETY"),200);
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
	@Test void composerRequiresCurrentPublishedPrivacyConsent() throws Exception {
		Browser b=composerMember();
		jdbc.update("UPDATE `serviceDocument` SET `isCurrent`=0 WHERE `code`='PRIVACY_PROCESSING'");
		jdbc.update("INSERT INTO `serviceDocument` (`code`,`version`,`title`,`body`,`isConsent`,`isRequired`,`isCurrent`,`publishedAt`) VALUES ('PRIVACY_PROCESSING',2,'synthetic','synthetic',1,1,1,?)",time(START));
		code(composer(b,""),403,"CONSENT_REQUIRED");
		jdbc.update("UPDATE `consentEvent` SET `documentVersion`=2 WHERE `userId`=? AND `documentCode`='PRIVACY_PROCESSING'",bin(userId(b)));
		status(composer(b,""),200);
		jdbc.update("UPDATE `serviceDocument` SET `publishedAt`=? WHERE `code`='PRIVACY_PROCESSING' AND `version`=2",time(START.plusSeconds(1)));
		code(composer(b,""),403,"CONSENT_REQUIRED");
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
		jdbc.update("UPDATE `webSession` SET `onboardingStep`='COMPLETE' WHERE `id`=?",bin(sessionId(other)));
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

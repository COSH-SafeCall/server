package com.safecall.service.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.api.AuthController;
import com.safecall.service.common.error.ErrorCode;
import com.safecall.service.common.error.ErrorResponse;

@Configuration
public class OpenApiConfiguration {
	private static final String BEARER = "accessToken";
	private static final String AUTH_TAG = "1. 인증";
	private static final String SESSION_TAG = "2. 세션·온보딩";
	private final JsonMapper mapper;

	public OpenApiConfiguration(JsonMapper mapper) { this.mapper = mapper; }

	@Bean
	public OpenAPI safeCallOpenApi() {
		var components = new Components().addSecuritySchemes(BEARER, new SecurityScheme()
			.type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
			.description("A01/A02의 tokens.accessToken 또는 A03의 accessToken 값만 입력하세요. Bearer 접두사는 UI가 붙입니다."));
		ModelConverters.getInstance().readAll(ErrorResponse.class).forEach(components::addSchemas);
		return new OpenAPI().components(components).info(new Info().title("SafeCall API 테스트")
			.version("2.1 / A01–A07")
			.description("""
				구현된 인증·온보딩 API를 직접 테스트합니다.

				1. A01 게스트 세션 발급 또는 A02 카카오 로그인에서 토큰을 받습니다.
				2. 우측 Authorize에 access token 값만 넣고 A05/A06/A07을 호출합니다.
				3. A03 갱신 후에는 Authorize 값을 새 access token으로 교체합니다.

				POST의 Idempotency-Key는 새 행동마다 새 UUID로 바꾸고, 같은 요청을 재시도할 때는 유지하세요.
				A01의 installationId와 bootstrapSecret은 테스트 설치별 난수를 사용하세요.
				예제의 고정 값은 합성 테스트용입니다. 실제 앱에서 공유하면 안 됩니다.

				A02에는 해당 카카오 앱에서 발급한 실제 SDK access token이 필요합니다.
				현재 2장 프로필 확인·동의 API가 없으므로 신규 회원은 PROFILE 이후 진행이 제한됩니다.
				게스트는 PERMISSIONS → SOS_GUIDE → COMPLETE까지 테스트할 수 있습니다.
				"""))
			.tags(List.of(new Tag().name(AUTH_TAG).description("A01~A04: 발급·갱신·로그아웃"),
				new Tag().name(SESSION_TAG).description("A05~A07: 현재 세션과 온보딩")));
	}

	@Bean
	public OperationCustomizer authOperations() {
		return (operation, handler) -> {
			if (!AuthController.class.isAssignableFrom(handler.getBeanType())) return operation;
			String method = handler.getMethod().getName();
			String id = switch (method) {
				case "guest" -> "A01"; case "kakao" -> "A02"; case "refresh" -> "A03";
				case "logout" -> "A04"; case "session" -> "A05"; case "onboarding" -> "A06";
				case "advance" -> "A07"; default -> throw new IllegalStateException("Undocumented auth operation.");
			};
			String summary = switch (id) {
				case "A01" -> "게스트 세션 발급";
				case "A02" -> "카카오 로그인";
				case "A03" -> "토큰 갱신";
				case "A04" -> "로그아웃";
				case "A05" -> "현재 세션 조회";
				case "A06" -> "온보딩 현재 단계 조회";
				default -> "온보딩 단계 확인";
			};
			operation.setOperationId(id);
			operation.setSummary(id + " · " + summary);
			operation.setTags(List.of(List.of("A01","A02","A03","A04").contains(id) ? AUTH_TAG : SESSION_TAG));
			operation.setDescription(description(id));
			var security = new ArrayList<SecurityRequirement>();
			if (!id.equals("A03")) security.add(new SecurityRequirement().addList(BEARER));
			if (List.of("A01","A02","A03").contains(id)) security.add(new SecurityRequirement());
			operation.setSecurity(security);
			if (operation.getParameters() != null) {
				// OpenAPI의 Authorization 일반 헤더는 Swagger UI가 전송하지 않으므로 보안 스킴으로 표현한다.
				operation.getParameters().removeIf(parameter -> "Authorization".equalsIgnoreCase(parameter.getName()));
				operation.getParameters().stream().filter(parameter -> "Idempotency-Key".equals(parameter.getName())).forEach(parameter -> {
					parameter.setRequired(true);
					parameter.setDescription("새 행동마다 새 UUID. 동일 요청 재시도는 같은 키·본문을 유지합니다. 예제 키는 직접 교체하세요.");
					parameter.setExample("11111111-1111-4111-8111-111111111111");
				});
			}
			var requestBody = operation.getRequestBody();
			if (requestBody != null && requestBody.getContent() != null) {
				requestBody.getContent().values().forEach(media -> media.setExamples(examples(id)));
			}
			String success = id.equals("A01") ? "201" : id.equals("A04") ? "204" : "200";
			ApiResponse response = operation.getResponses().remove("200");
			if (response == null) response = operation.getResponses().get(success);
			if (response == null) response = new ApiResponse();
			response.setDescription(id.equals("A04") ? "현재 회원 세션 종료. 응답 본문 없음." : "성공");
			if (id.equals("A04")) response.setContent(null);
			response.addHeaderObject("X-Request-Id", new io.swagger.v3.oas.models.headers.Header()
				.description("서버 생성 요청 식별자").schema(new Schema<String>().type("string").format("uuid")));
			response.addHeaderObject("Cache-Control", new io.swagger.v3.oas.models.headers.Header()
				.description("개인정보 응답 캐시 금지").schema(new Schema<String>().type("string").example("no-store")));
			operation.getResponses().addApiResponse(success, response);
			for (ErrorCode code : errors(id)) {
				String status = Integer.toString(code.status());
				ApiResponse error = operation.getResponses().get(status);
				if (error == null) {
					error = new ApiResponse().description("공통 오류 응답").content(new Content().addMediaType("application/json",
						new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))));
					operation.getResponses().addApiResponse(status, error);
				}
				String path = switch (id) {
					case "A01" -> "/auth/guest"; case "A02" -> "/auth/kakao"; case "A03" -> "/auth/refresh";
					case "A04" -> "/auth/logout"; case "A05" -> "/session"; case "A06" -> "/onboarding";
					default -> "/onboarding/advance";
				};
				error.getContent().get("application/json").addExamples(code.name(), new Example().summary(code.message())
					.value(Map.of("timestamp","2026-09-09T00:00:00.000000","status",code.status(),"code",code.name(),
						"message",code.message(),"errors",List.of(),"path","/api/v1" + path)));
				if (code == ErrorCode.RATE_LIMITED || code == ErrorCode.REQUEST_IN_PROGRESS) {
					error.addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
						.description("재시도까지 대기할 초").schema(new Schema<Integer>().type("integer").minimum(java.math.BigDecimal.ONE)));
				}
			}
			return operation;
		};
	}

	@Bean
	public OpenApiCustomizer authSchemaContracts() {
		return api -> {
			// 모든 응답 필드는 null인 경우에도 존재한다. 게스트/누락 프로필의 null을 스키마에 명시한다.
			for (String name : List.of("AuthResponse","Tokens","SessionView","ProfileView","OnboardingView","ErrorResponse","FieldError")) {
				Schema<?> schema = api.getComponents().getSchemas().get(name);
				if (schema != null && schema.getProperties() != null) schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
			}
			for (var entry : Map.of("AuthResponse",List.of("profile"), "SessionView",List.of("userId"),
				"ProfileView",List.of("name","birthDate","phone","confirmedAt"), "FieldError",List.of("value")).entrySet()) {
				Schema<?> schema = api.getComponents().getSchemas().get(entry.getKey());
				if (schema != null) for (String field : entry.getValue()) {
					Schema<?> property = (Schema<?>) schema.getProperties().get(field);
					if (property != null && property.get$ref() != null) {
						schema.addProperty(field, new Schema<>().allOf(List.of(new Schema<>().$ref(property.get$ref()))).nullable(true));
					} else if (property != null) property.setNullable(true);
				}
			}
			for (String name : List.of("GuestRequest","KakaoRequest","RefreshRequest","EmptyRequest","PermissionReview","AdvanceRequest")) {
				Schema<?> schema = api.getComponents().getSchemas().get(name);
				if (schema != null) schema.setAdditionalProperties(false);
			}
			Schema<?> advance = api.getComponents().getSchemas().get("AdvanceRequest");
			if (advance != null) advance.addProperty("step", new Schema<String>().type("string")
				._enum(List.of("PROFILE","CONTACTS","CONSENTS","PERMISSIONS","SOS_GUIDE","MESSAGE_TEST")));
		};
	}

	private String description(String id) {
		return switch (id) {
			case "A01" -> "최초 호출은 Authorize 없이 실행합니다. 같은 설치를 새 요청으로 교체할 때만 현재 access token 또는 currentRefreshToken이 필요합니다. bootstrapSecret은 Base64URL로 인코딩한 난수 32바이트 이상입니다. 최초 응답 재생은 60초입니다.";
			case "A02" -> "kakaoAccessToken에는 카카오 SDK의 access token을 입력합니다. SafeCall JWT와 다릅니다. 신규 로그인은 Authorize 없이 가능하며, 활성 설치 세션을 교체할 때는 기존 세션의 증명이 필요합니다. 현재 회원의 사용자 확인 정보와 동의 철회 결과는 덮어쓰지 않습니다.";
			case "A03" -> "본문에 SafeCall refreshToken을 입력합니다. Authorization은 사용하지 않습니다. 같은 토큰·키는 30초간 재생됩니다. 소비된 refreshToken을 다른 키로 재사용하면 세션이 폐기됩니다. 갱신 성공 후 Authorize의 access token도 교체하세요.";
			case "A04" -> "KAKAO 세션 전용입니다. Authorize 후 빈 객체 {}로 실행합니다. 현재 세션·열린 통화를 종료하며 다른 기기는 유지합니다. 같은 증명·키의 결과 확인은 60초 이내, 기존 access token 만료 전까지 가능합니다.";
			case "A05" -> "Authorize에 현재 SafeCall access token을 입력하세요. 게스트와 회원 모두 조회할 수 있습니다.";
			case "A06" -> "A07에 넣을 현재 step·version과 진행 조건을 확인합니다. isAdvanceAllowed는 서버 데이터 조건이며 OS 권한 허용 여부가 아닙니다.";
			default -> "먼저 A06의 step과 version을 확인하고 맞는 예제를 선택하세요. expectedVersion과 Idempotency-Key를 갱신해야 합니다. PERMISSIONS에서만 permissionReview, MESSAGE_TEST에서만 testDecision을 보냅니다. 게스트의 location 필드는 제거하세요. 권한 거부도 진행 가능하며 FINISH는 문자 전송 성공을 뜻하지 않습니다.";
		};
	}
	private List<ErrorCode> errors(String id) {
		var codes = new ArrayList<>(List.of(ErrorCode.INVALID_REQUEST, ErrorCode.VALIDATION_FAILED,
			ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.NOT_ACCEPTABLE));
		if (!List.of("A05","A06").contains(id)) codes.addAll(List.of(ErrorCode.IDEMPOTENCY_CONFLICT,
			ErrorCode.REQUEST_IN_PROGRESS, ErrorCode.UNSUPPORTED_MEDIA_TYPE));
		if (List.of("A01","A02","A03").contains(id)) codes.add(ErrorCode.RATE_LIMITED);
		if (!id.equals("A03")) codes.add(ErrorCode.AUTHENTICATION_REQUIRED);
		if (List.of("A01","A02").contains(id)) codes.add(ErrorCode.AUTH_REPLAY_EXPIRED);
		if (!id.equals("A01")) codes.add(ErrorCode.SESSION_EXPIRED);
		if (id.equals("A02")) codes.addAll(List.of(ErrorCode.KAKAO_TOKEN_INVALID, ErrorCode.KAKAO_UNAVAILABLE));
		if (List.of("A02","A03","A05","A06","A07").contains(id)) codes.add(ErrorCode.ACCOUNT_DELETION_PENDING);
		if (id.equals("A03")) codes.addAll(List.of(ErrorCode.TOKEN_REUSED, ErrorCode.REFRESH_REPLAY_EXPIRED));
		if (id.equals("A04")) codes.addAll(List.of(ErrorCode.LOGIN_REQUIRED, ErrorCode.LOGOUT_FAILED));
		if (id.equals("A07")) codes.addAll(List.of(ErrorCode.VERSION_CONFLICT, ErrorCode.ONBOARDING_STEP_MISMATCH,
			ErrorCode.PROFILE_INCOMPLETE, ErrorCode.CONSENT_REQUIRED));
		return codes;
	}
	private Map<String,Example> examples(String id) {
		Map<String,Example> examples = new LinkedHashMap<>();
		switch (id) {
			case "A01" -> example(examples, "guest", "새 테스트 설치로 게스트 발급", """
				{"installationId":"66666666-6666-4666-8666-666666666666","platform":"ANDROID","appVersion":"1.0.0","osVersion":"16","bootstrapSecret":"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY"}
				""");
			case "A02" -> example(examples, "kakao", "카카오 SDK access token으로 로그인", """
				{"installationId":"77777777-7777-4777-8777-777777777777","platform":"ANDROID","appVersion":"1.0.0","osVersion":"16","kakaoAccessToken":"카카오_SDK_access_token을_입력하세요"}
				""");
			case "A03" -> example(examples, "refresh", "발급받은 SafeCall refresh token", """
				{"refreshToken":"발급받은_refreshToken을_입력하세요"}
				""");
			case "A04" -> example(examples, "logout", "빈 객체", "{}");
			case "A07" -> {
				example(examples,"guestPermissions","게스트 · 마이크 안내 확인","""
					{"step":"PERMISSIONS","expectedVersion":1,"permissionReview":{"microphone":"GRANTED"}}
					""");
				example(examples,"memberPermissions","회원 · 마이크·위치 안내 확인","""
					{"step":"PERMISSIONS","expectedVersion":4,"permissionReview":{"microphone":"GRANTED","location":"DENIED"}}
					""");
				example(examples,"sosGuide","SOS 안내 확인 (게스트 예시)","""
					{"step":"SOS_GUIDE","expectedVersion":2}
					""");
				example(examples,"profile","회원 · 기본 정보 확인","""
					{"step":"PROFILE","expectedVersion":1}
					""");
				example(examples,"contacts","회원 · 연락망 안내 확인","""
					{"step":"CONTACTS","expectedVersion":2}
					""");
				example(examples,"consents","회원 · 필수 동의 확인","""
					{"step":"CONSENTS","expectedVersion":3}
					""");
				example(examples,"skipTest","회원 · 메시지 테스트 건너뛰기","""
					{"step":"MESSAGE_TEST","expectedVersion":6,"testDecision":"SKIP"}
					""");
				example(examples,"finishTest","회원 · 메시지 테스트 화면 종료","""
					{"step":"MESSAGE_TEST","expectedVersion":6,"testDecision":"FINISH"}
					""");
			}
			default -> { }
		}
		return examples;
	}
	private void example(Map<String,Example> examples, String name, String summary, String json) {
		examples.put(name, new Example().summary(summary).value(mapper.readValue(json, Object.class)));
	}
}

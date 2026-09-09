package com.safecall.service.common.config;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import com.safecall.service.common.error.ErrorCode;
import com.safecall.service.user.api.UserController;
import com.safecall.service.user.api.DocumentController;

@Configuration
public class UserOpenApiConfiguration {
	@Bean
	public OperationCustomizer userOperations() {
		return (operation,handler) -> {
			if (handler.getBeanType()!=UserController.class && handler.getBeanType()!=DocumentController.class) return operation;
			String id=operation.getOperationId();
			if (operation.getParameters()!=null) for (var parameter:operation.getParameters()) {
				if (parameter.getName().equals("Idempotency-Key")) {
					parameter.setExample("11111111-1111-4111-8111-111111111111");
					parameter.setDescription("새 행동마다 새 UUID. 재시도는 동일한 키·본문·대상을 유지하세요.");
				}
				if (parameter.getName().equals("expectedVersion")) parameter.setExample(1);
				if (parameter.getName().equals("code")) parameter.setExample("AI_CALL");
			}
			if (id.equals("U03")) {
				operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("If-None-Match").in("header")
					.description("이전 응답의 ETag. 일치하면 본문 없는 304.").schema(new StringSchema()));
				operation.getResponses().addApiResponse("304",new ApiResponse().description("문서 변경 없음"));
			}
			if (id.equals("U08") || id.equals("U10")) {
				ApiResponse response=operation.getResponses().remove("200");
				if (response==null) response=new ApiResponse();
				response.setDescription("성공");
				if (id.equals("U10")) response.setContent(null);
				operation.getResponses().addApiResponse(id.equals("U08") ? "201" : "204",response);
			}
			if (operation.getRequestBody()!=null) {
				Object example=switch(id) {
					case "U02" -> Map.of("name","홍길동","gender","MALE","birthDate","2000-01-01","phone","01012345678","expectedVersion",1);
					case "U05" -> Map.of("decisions",List.of(Map.of("code","PRIVACY_PROCESSING","version",1,"action","GRANTED"),Map.of("code","AI_CALL","version",1,"action","GRANTED")));
					case "U06" -> Map.of("version",1);
					case "U08" -> Map.of("name","김보호","relationship","가족","phone","01098765432");
					case "U09" -> Map.of("name","김보호","relationship","가족","phone","01098765432","expectedVersion",1);
					case "U12" -> Map.of("incomingAlertMode","VIBRATE","expectedVersion",1);
					default -> Map.of();
				};
				operation.getRequestBody().getContent().values().forEach(media -> media.addExamples("request",new Example().summary("합성 테스트 예제; 버전은 조회 결과로 교체").value(example)));
			}
			for (ErrorCode code:errors(id)) {
				String status=Integer.toString(code.status());
				ApiResponse response=operation.getResponses().get(status);
				if (response==null) {
					response=new ApiResponse().description("공통 오류; HTTP status와 code를 확인하세요.").content(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))));
					operation.getResponses().addApiResponse(status,response);
				}
			}
			return operation;
		};
	}
	private List<ErrorCode> errors(String id) {
		var codes=new ArrayList<>(List.of(ErrorCode.INVALID_REQUEST,ErrorCode.VALIDATION_FAILED,ErrorCode.INTERNAL_SERVER_ERROR));
		if (id.equals("U03")) { codes.add(ErrorCode.DOCUMENT_CODE_INVALID); return codes; }
		codes.addAll(List.of(ErrorCode.AUTHENTICATION_REQUIRED,ErrorCode.SESSION_EXPIRED,ErrorCode.LOGIN_REQUIRED,ErrorCode.ACCOUNT_DELETION_PENDING,ErrorCode.REQUEST_IN_PROGRESS));
		if (List.of("U05","U06","U08","U10").contains(id)) codes.add(ErrorCode.IDEMPOTENCY_CONFLICT);
		if (List.of("U02","U09","U10","U12").contains(id)) codes.add(ErrorCode.VERSION_CONFLICT);
		if (List.of("U02","U08","U09").contains(id)) codes.addAll(List.of(ErrorCode.INVALID_PHONE,ErrorCode.CONTACT_PHONE_DUPLICATE));
		if (id.equals("U02")) codes.addAll(List.of(ErrorCode.INVALID_BIRTH_DATE,ErrorCode.CONSENT_REQUIRED,ErrorCode.DATA_CLEANUP_PENDING));
		if (id.equals("U08")) codes.addAll(List.of(ErrorCode.CONTACT_LIMIT_REACHED,ErrorCode.CONTACT_NOT_FOUND));
		if (List.of("U09","U10").contains(id)) codes.add(ErrorCode.CONTACT_NOT_FOUND);
		if (List.of("U05","U06").contains(id)) codes.addAll(List.of(ErrorCode.CONSENT_VERSION_CHANGED,ErrorCode.DOCUMENT_CODE_INVALID,ErrorCode.NOT_A_CONSENT_DOCUMENT,ErrorCode.DATA_CLEANUP_PENDING));
		if (id.equals("U05")) codes.add(ErrorCode.WITHDRAWAL_REQUIRED);
		if (id.equals("U06")) codes.add(ErrorCode.DELETION_RECEIPT_EXPIRED);
		if (id.equals("U12")) codes.add(ErrorCode.SETTINGS_SAVE_FAILED);
		return codes;
	}
	@Bean
	public OpenApiCustomizer userSchemas() {
		return api -> {
			for (String name:List.of("ProfileRequest","ContactRequest","ContactUpdate","DecisionsRequest","Decision","WithdrawRequest","SettingRequest")) {
				Schema<?> schema=api.getComponents().getSchemas().get(name);
				if (schema!=null) {
					schema.setAdditionalProperties(false);
					if (schema.getProperties()!=null) schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
				}
			}
			Schema<?> profile=api.getComponents().getSchemas().get("ProfileRequest");
			if (profile!=null) ((Schema<?>)profile.getProperties().get("birthDate")).setNullable(true);
		};
	}
}

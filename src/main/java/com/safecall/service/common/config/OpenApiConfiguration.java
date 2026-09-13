package com.safecall.service.common.config;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.*;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
@Configuration
public class OpenApiConfiguration {
	@Bean public OpenAPI safeCallApi(){return new OpenAPI().info(new Info().title("SafeCall Web MVP").version("4.2-web-mvp"))
		.components(new Components().addSecuritySchemes("webSession",new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("__Host-safecall-session")));}
	@Bean public OperationCustomizer webContracts(){return (operation,handler)->{
		String id=operation.getOperationId();
		if(id==null)return operation;
		String success=Map.of("A02_CALLBACK","303","A04","204","U06","202","U08","201","U10","204","C01","202","C07","202").get(id);
		if(success!=null && !operation.getResponses().containsKey(success)) {
			var response=operation.getResponses().remove("200");
			operation.getResponses().addApiResponse(success,response==null?new io.swagger.v3.oas.models.responses.ApiResponse().description("Success"):response);
		}
		if(!Set.of("A05","A02_CALLBACK","U03").contains(id))operation.setSecurity(List.of(new SecurityRequirement().addList("webSession")));
		if(!Set.of("A05","A02_CALLBACK","A06","U01","U03","U04","U07","U11","H01","H02","C02","C03","M01").contains(id)) {
			operation.addParametersItem(new Parameter().in("header").name("X-CSRF-Token").required(true).schema(new StringSchema()).description("A05에서 받은 현재 세션의 CSRF 토큰"));
			operation.addParametersItem(new Parameter().in("header").name("Origin").required(true).schema(new StringSchema()).description("WEB_ORIGIN과 같은 origin; 브라우저가 설정"));
		}
		return operation;};}
	@Bean public OpenApiCustomizer strictSchemas(){return api->{if(api.getComponents().getSchemas()!=null)api.getComponents().getSchemas().values().forEach(s->{if("object".equals(s.getType()))s.setAdditionalProperties(false);});};}
}

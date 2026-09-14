package com.safecall.service.common.config;

import java.io.IOException;
import java.io.InputStream;
import org.springdoc.core.properties.SwaggerUiConfigParameters;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true", matchIfMissing = true)
public class SwaggerUiConfiguration {

	@Bean
	SwaggerIndexTransformer swaggerIndexTransformer(SwaggerUiConfigProperties config, SwaggerUiOAuthProperties oauth,
			SwaggerWelcomeCommon welcome, ObjectMapperProvider mapper) {
		return new SwaggerIndexPageTransformer(config, oauth, welcome, mapper) {
			@Override
			protected String defaultTransformations(SwaggerUiConfigParameters parameters, InputStream source) throws IOException {
				String initializer = super.defaultTransformations(parameters, source);
				// A JavaScript comparator must be a function, not a string in swagger-config JSON.
				return initializer.replace("window.ui = SwaggerUIBundle({", """
					window.ui = SwaggerUIBundle({
					  operationsSorter: (a, b) => {
					    const left = a.get("operation");
					    const right = b.get("operation");
					    const order = (left.get("x-display-order") ?? 999) - (right.get("x-display-order") ?? 999);
					    return order || String(left.get("operationId")).localeCompare(String(right.get("operationId")));
					  },
					""");
			}
		};
	}
}

package com.safecall.service.common.config;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration
@EnableScheduling
public class AppConfiguration {
	@Bean
	public Clock clock() { return Clock.systemUTC(); }
	@Bean
	public org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictJson() {
		return builder -> builder.disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
	}
}

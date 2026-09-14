package com.safecall.service.common.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenApiConfigurationTest {
	@Test void openApiUsesSameOriginRelativeServer() {
		var api = new OpenApiConfiguration().safeCallApi();
		assertThat(api.getServers()).singleElement().extracting("url").isEqualTo("/");
	}
}

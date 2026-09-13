package com.safecall.service.telemetry.service;

import static org.assertj.core.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TelemetryPolicyTest {
	@Test void invalidOrUnboundedReleaseIdentifiersFailAtStartup() {
		for(String value:Arrays.asList(null,""," ","a".repeat(41),"https://example.test","v1\n","release/name"))
			assertThatThrownBy(()->new TelemetryPolicy(value)).isInstanceOf(IllegalArgumentException.class);
	}
	@Test void publicReleaseIdentifiersMayContainBuildMetadata() {
		assertThat(new TelemetryPolicy("4.2-web-mvp+build.7").webVersion()).isEqualTo("4.2-web-mvp+build.7");
	}
}

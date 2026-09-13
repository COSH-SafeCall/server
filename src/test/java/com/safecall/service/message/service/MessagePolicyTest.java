package com.safecall.service.message.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MessagePolicyTest {
	// 형식 검증용 합성 경로이며 실제 지도 기능 검수 근거가 아니다.
	private static final String URL = "https://map.naver.com/synthetic?lat={latitude}&lon={longitude}";
	@Test void unconfiguredMapIsNull() {
		assertThat(new MessagePolicy(0,"","","","","").mapTemplate()).isNull();
	}
	@Test void reviewedConfigurationExposesOnlyPublicTemplateFields() {
		var template = new MessagePolicy(3,URL,"LAT_LON","map.naver.com","synthetic-browser","test-only").mapTemplate();
		assertThat(template.version()).isEqualTo(3);
		assertThat(template.urlTemplate()).isEqualTo(URL);
		assertThat(template.coordinateSystem()).isEqualTo("WGS84");
		assertThat(template.maxAgeSeconds()).isEqualTo(30);
		assertThat(template.maxAccuracyMeters()).isEqualTo(100);
	}
	@Test void missingReviewOrWrongCoordinateOrderCannotEnableMap() {
		assertThatThrownBy(() -> new MessagePolicy(3,URL,"LAT_LON","map.naver.com","","")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new MessagePolicy(3,URL,"LON_LAT","map.naver.com","synthetic","test-only")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new MessagePolicy(0,URL,"LAT_LON","map.naver.com","synthetic","test-only")).isInstanceOf(IllegalStateException.class);
	}
	@Test void unsafeHostsSchemesAndPlaceholdersAreRejected() {
		for (String url : new String[]{URL.replace("https:","http:"), URL.replace("map.naver.com","evil.test"),
			URL.replace("map.naver.com","map.naver.com@evil.test"), URL.replace("{latitude}","37.5"),
			URL+"&extra={latitude}", URL+"&unknown={secret}", URL.replace("map.naver.com","map.naver.com:444")}) {
			assertThatThrownBy(() -> new MessagePolicy(3,url,"LAT_LON","map.naver.com","synthetic","test-only"))
				.as(url).isInstanceOf(IllegalStateException.class);
		}
	}
	@Test void reviewedLongitudeFirstTemplateIsSupported() {
		var template = new MessagePolicy(4,"https://map.naver.com/synthetic?point={longitude},{latitude}",
			"LON_LAT","map.naver.com","synthetic","test-only").mapTemplate();
		assertThat(template.version()).isEqualTo(4);
	}
}

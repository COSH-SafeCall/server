package com.safecall.service.message.service;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.safecall.service.message.api.MessageDtos.MapTemplate;

@Component
public class MessagePolicy {
	private final MapTemplate mapTemplate;
	public MessagePolicy(@Value("${app.message.map.version:0}") int version,
		@Value("${app.message.map.url-template:}") String urlTemplate,
		@Value("${app.message.map.coordinate-order:}") String coordinateOrder,
		@Value("${app.message.map.allowed-host:}") String allowedHost,
		@Value("${app.message.map.reviewed-browsers:}") String reviewedBrowsers,
		@Value("${app.message.map.validation-ref:}") String validationRef) {
		if (version==0 && List.of(urlTemplate,coordinateOrder,allowedHost,reviewedBrowsers,validationRef).stream().allMatch(String::isBlank)) {
			mapTemplate = null;
			return;
		}
		try {
			if (version<1 || List.of(urlTemplate,coordinateOrder,allowedHost,reviewedBrowsers,validationRef).stream()
				.anyMatch(value -> value.isBlank() || value.chars().anyMatch(Character::isISOControl))) throw new IllegalArgumentException();
			if (!List.of("LAT_LON","LON_LAT").contains(coordinateOrder)
				|| !once(urlTemplate,"{latitude}") || !once(urlTemplate,"{longitude}")) throw new IllegalArgumentException();
			String example = urlTemplate.replace("{latitude}","37.5").replace("{longitude}","127.0");
			URI uri = URI.create(example);
			if (!"https".equals(uri.getScheme()) || !allowedHost.equals(uri.getHost()) || uri.getRawUserInfo()!=null
				|| uri.getPort()!=-1 && uri.getPort()!=443 || uri.getRawAuthority().contains("37.5") || uri.getRawAuthority().contains("127.0")
				|| !(allowedHost.equals("naver.com") || allowedHost.endsWith(".naver.com") || allowedHost.equals("naver.me")))
				throw new IllegalArgumentException();
			boolean isLatitudeFirst = urlTemplate.indexOf("{latitude}") < urlTemplate.indexOf("{longitude}");
			if (isLatitudeFirst != coordinateOrder.equals("LAT_LON")) throw new IllegalArgumentException();
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Invalid reviewed map template configuration.");
		}
		mapTemplate = new MapTemplate(version,urlTemplate,"WGS84",30,100);
	}
	private static boolean once(String value, String token) {
		return value.contains(token) && value.indexOf(token)==value.lastIndexOf(token);
	}
	public MapTemplate mapTemplate() { return mapTemplate; }
}

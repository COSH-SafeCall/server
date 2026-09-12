package com.safecall.service.call.gemini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiSettings {
	private final int connectionSeconds;
	private final int newSessionSeconds;
	public GeminiSettings(@Value("${app.gemini.connection-ttl-seconds}") int connectionSeconds,
		@Value("${app.gemini.new-session-ttl-seconds}") int newSessionSeconds) {
		if (connectionSeconds<1 || connectionSeconds>600 || newSessionSeconds<1 || newSessionSeconds>60
			|| newSessionSeconds>connectionSeconds) throw new IllegalStateException("Invalid Gemini lifetime configuration.");
		this.connectionSeconds=connectionSeconds; this.newSessionSeconds=newSessionSeconds;
	}
	public int connectionSeconds() { return connectionSeconds; }
	public int newSessionSeconds() { return newSessionSeconds; }
}

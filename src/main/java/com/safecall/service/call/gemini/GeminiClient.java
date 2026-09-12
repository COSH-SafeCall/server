package com.safecall.service.call.gemini;

import java.time.Instant;

public interface GeminiClient {
	boolean isConfigured();
	String issue(IssueRequest request);
	record IssueRequest(String model, String apiVersion, String voiceId, String instruction,
		Instant newSessionExpiresAt, Instant expiresAt, java.util.UUID grantId, String purpose) {
		public IssueRequest(String model,String apiVersion,String voiceId,String instruction,Instant newSessionExpiresAt,Instant expiresAt){this(model,apiVersion,voiceId,instruction,newSessionExpiresAt,expiresAt,null,"INITIAL");}
		@Override public String toString() { return "IssueRequest[redacted]"; }
	}
	final class IssueException extends RuntimeException {
		private final boolean isUnknown;
		public IssueException(boolean isUnknown) { super("Gemini token issuance failed."); this.isUnknown=isUnknown; }
		public boolean isUnknown() { return isUnknown; }
	}
}

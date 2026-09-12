package com.safecall.service.call.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record CallPolicy(String version,int maxResumeAttempts,int resumeDelayMs,int leaseSeconds,int issueTimeoutSeconds,
	int minuteLimit,int guestDailyLimit,int memberDailyLimit,int renewalMinuteLimit,String validatedModel,String validationRef,int modelMaxSeconds) {
	public CallPolicy(@Value("${app.call.policy-version:mvp-2026-09-11}") String version,
		@Value("${app.call.max-resume-attempts:1}") int maxResumeAttempts,@Value("${app.call.resume-delay-ms:1000}") int resumeDelayMs,
		@Value("${app.call.lease-seconds:30}") int leaseSeconds,@Value("${app.call.issue-timeout-seconds:10}") int issueTimeoutSeconds,
		@Value("${app.call.minute-limit:5}") int minuteLimit,@Value("${app.call.guest-daily-limit:20}") int guestDailyLimit,
		@Value("${app.call.member-daily-limit:100}") int memberDailyLimit,@Value("${app.call.renewal-minute-limit:5}") int renewalMinuteLimit,
		@Value("${app.gemini.validated-model:}") String validatedModel,@Value("${app.gemini.validation-ref:}") String validationRef,
		@Value("${app.gemini.model-max-seconds:0}") int modelMaxSeconds) {
		if(version.isBlank()||version.length()>32||maxResumeAttempts<0||resumeDelayMs<0||leaseSeconds<=5||issueTimeoutSeconds<1||issueTimeoutSeconds>=leaseSeconds
			||minuteLimit<1||guestDailyLimit<1||memberDailyLimit<1||renewalMinuteLimit<1||modelMaxSeconds<0)throw new IllegalStateException("Invalid call policy.");
		this.version=version;this.maxResumeAttempts=maxResumeAttempts;this.resumeDelayMs=resumeDelayMs;this.leaseSeconds=leaseSeconds;this.issueTimeoutSeconds=issueTimeoutSeconds;
		this.minuteLimit=minuteLimit;this.guestDailyLimit=guestDailyLimit;this.memberDailyLimit=memberDailyLimit;this.renewalMinuteLimit=renewalMinuteLimit;
		this.validatedModel=validatedModel;this.validationRef=validationRef;this.modelMaxSeconds=modelMaxSeconds;
	}
	public boolean isValidated(String model){return modelMaxSeconds>0&&!validationRef.isBlank()&&validatedModel.equals(model);}
}

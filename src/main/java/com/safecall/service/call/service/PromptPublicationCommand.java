package com.safecall.service.call.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** An explicit deployment command, not an automatic seed or a public HTTP endpoint. */
@Component
@ConditionalOnProperty(name="app.prompts.publish-release-id")
public class PromptPublicationCommand implements ApplicationRunner {
	private final PromptPublicationService service;
	private final UUID id;
	private final String validationRef;
	public PromptPublicationCommand(PromptPublicationService service,@Value("${app.prompts.publish-release-id}") UUID id,
		@Value("${app.prompts.validation-ref:}") String validationRef) { this.service=service;this.id=id;this.validationRef=validationRef; }
	@Override public void run(ApplicationArguments args) {
		service.publish(id,validationRef);
		org.slf4j.LoggerFactory.getLogger(getClass()).info("Reviewed prompt release published: {}",id);
	}
}

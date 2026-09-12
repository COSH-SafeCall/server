package com.safecall.service.call.service;

import static com.safecall.service.auth.repository.AuthRepository.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.call.repository.CallRepository;
import com.safecall.service.common.error.*;

/** Deployment-only publication; never exposed as an app API. Published content has no edit path. */
@Service
public class PromptPublicationService {
	private final JdbcTemplate jdbc;
	private final CallRepository repository;
	private final Clock clock;
	public PromptPublicationService(JdbcTemplate jdbc,CallRepository repository,Clock clock) { this.jdbc=jdbc;this.repository=repository;this.clock=clock; }
	@Transactional
	public void publish(UUID id,String validationRef) {
		if(validationRef==null||validationRef.isBlank()||validationRef.length()>500||validationRef.chars().anyMatch(Character::isISOControl))
			throw new CustomException(ErrorCode.VALIDATION_FAILED);
		// Serialize competing releases in stable order; the UNIQUE published marker is the final guard.
		jdbc.queryForList("SELECT `id` FROM `promptRelease` ORDER BY `id` FOR UPDATE");
		var rows=jdbc.queryForList("SELECT `status`,`validationRef` FROM `promptRelease` WHERE `id`=?",bin(id));
		if(rows.isEmpty())throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
		var row=rows.getFirst();
		CallService.validatePrompts(repository.draftPrompts(id),repository.scenarios());
		if("PUBLISHED".equals(row.get("status")) && validationRef.equals(row.get("validationRef")))return;
		if(!"DRAFT".equals(row.get("status")))throw new CustomException(ErrorCode.VERSION_CONFLICT);
		jdbc.update("UPDATE `promptRelease` SET `status`='RETIRED' WHERE `status`='PUBLISHED'");
		jdbc.update("UPDATE `promptRelease` SET `status`='PUBLISHED',`validationRef`=?,`publishedAt`=? WHERE `id`=?",validationRef,time(clock.instant()),bin(id));
	}
}

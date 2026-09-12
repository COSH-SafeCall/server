package com.safecall.service.user.service;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.common.error.*;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.repository.UserRepository;
@Service
public class DocumentService {
	private static final Set<String> CODES=Set.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING","PRIVACY_NOTICE","AI_POLICY","HELP","SOS_GUIDE","PRE_CALL_NOTICE");
	private final UserRepository repository;private final Clock clock;
	public DocumentService(UserRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
	public static void consentCode(String code){if(!Set.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING").contains(code))throw new CustomException(ErrorCode.INVALID_CONSENT);}
	@Transactional(readOnly=true)
	public DocumentView document(String code,Integer version){
		if(!CODES.contains(code))throw new CustomException(ErrorCode.DOCUMENT_CODE_INVALID);
		var d=repository.document(code,version);
		if(d==null || d.publishedAt().isAfter(clock.instant()))throw new CustomException(version==null?ErrorCode.DOCUMENT_NOT_READY:ErrorCode.RESOURCE_NOT_FOUND);
		return new DocumentView(d.code(),d.version(),d.title(),d.body(),d.isConsent(),d.isRequired(),d.publishedAt());
	}
}

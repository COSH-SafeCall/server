package com.safecall.service.user.service;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.common.error.*;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.repository.UserRepository;

@Service
public class DocumentService {
	private static final Map<String,String> PURPOSES = Map.of(
		"PRIVACY_PROCESSING","회원 정보와 비상 연락망을 이용한 서비스 제공",
		"AI_CALL","실시간 AI 음성 통화 제공", "LOCATION_PROCESSING","안심 메시지 작성 시 현재 위치 링크 포함",
		"PRIVACY_NOTICE","개인정보 처리 내용 안내", "AI_POLICY","AI 안심 통화의 안전 대화 원칙 안내",
		"HELP","서비스 사용 방법 안내", "SOS_GUIDE","기기 긴급 호출 설정 안내", "PRE_CALL_NOTICE","통화 전 이용 안내");
	private final UserRepository repository;
	public DocumentService(UserRepository repository) { this.repository = repository; }
	public static String purpose(String code) { return PURPOSES.get(code); }
	public static void consentCode(String code) {
		if (!PURPOSES.containsKey(code)) throw new CustomException(ErrorCode.DOCUMENT_CODE_INVALID);
		if (!Set.of("PRIVACY_PROCESSING","AI_CALL","LOCATION_PROCESSING").contains(code)) throw new CustomException(ErrorCode.NOT_A_CONSENT_DOCUMENT);
	}
	@Transactional(readOnly=true)
	public Items<DocumentView> documents(String codes) {
		Set<String> selected = new TreeSet<>(PURPOSES.keySet());
		if (codes != null) {
			selected.clear();
			for (String code : codes.split(",",-1)) {
				if (!PURPOSES.containsKey(code.strip())) throw new CustomException(ErrorCode.DOCUMENT_CODE_INVALID);
				selected.add(code.strip());
			}
		}
		return new Items<>(repository.documents(false).stream().filter(d -> selected.contains(d.code())).map(d ->
			new DocumentView(d.code(),d.version(),d.title(),d.body(),purpose(d.code()),d.isConsent(),d.isRequired(),d.publishedAt())).toList());
	}
}

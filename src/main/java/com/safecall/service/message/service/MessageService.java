package com.safecall.service.message.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.auth.api.AuthDtos.Step;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.crypto.UserKeyStore;
import com.safecall.service.common.error.*;
import com.safecall.service.message.api.MessageDtos.*;
import com.safecall.service.message.repository.MessageRepository;
import com.safecall.service.user.api.UserDtos.ContactView;

@Service
public class MessageService {
	private static final int TEMPLATE_VERSION = 3;
	private final AuthTransactions authentication;
	private final MessageRepository repository;
	private final UserKeyStore keys;
	private final SecretCrypto crypto;
	private final MessagePolicy policy;
	private final Clock clock;
	public MessageService(AuthTransactions authentication, MessageRepository repository, UserKeyStore keys,
		SecretCrypto crypto, MessagePolicy policy, Clock clock) {
		this.authentication=authentication; this.repository=repository; this.keys=keys;
		this.crypto=crypto; this.policy=policy; this.clock=clock;
	}

	@Transactional(isolation=Isolation.READ_COMMITTED, noRollbackFor=SessionInvalidException.class)
	public MessageComposerView compose(String cookie, Mode mode) {
		// 기존 계정→세션 잠금을 유지해 조회 도중 개인정보 키가 폐기되지 않게 한다.
		var session = authentication.member(cookie, true);
		var preparedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var rows = repository.snapshot(session.id(), preparedAt);
		if (rows.isEmpty()) throw new CustomException(ErrorCode.SESSION_EXPIRED);
		var material = rows.getFirst();
		if (material.isCleanupPending()) throw new CustomException(ErrorCode.DATA_CLEANUP_PENDING);
		if (!material.isPrivacyGranted()) throw new CustomException(ErrorCode.CONSENT_REQUIRED);
		if (material.step()!=Step.COMPLETE && !(mode==Mode.TEST && material.step()==Step.MESSAGE_TEST))
			throw new CustomException(ErrorCode.ONBOARDING_REQUIRED);
		if (!material.isConfirmed() || material.name()==null || material.phone()==null)
			throw new CustomException(ErrorCode.MESSAGE_PROFILE_REQUIRED);
		if (material.isCallOpen()) throw new CustomException(ErrorCode.CALL_ALREADY_OPEN);
		var contacts = rows.stream().map(MessageRepository.Material::contact).filter(java.util.Objects::nonNull).toList();
		if (contacts.isEmpty() || contacts.size()>2) throw new CustomException(ErrorCode.CONTACT_REQUIRED);

		byte[] key = keys.read(material.keyRef());
		String name = open(key, material.userId(), "name", material.name());
		String phone = open(key, material.userId(), "phone", material.phone());
		if (name.isBlank() || !phone.matches("010[0-9]{8}")) throw new CustomException(ErrorCode.MESSAGE_PROFILE_REQUIRED);
		var identity = new Identity(name, "010-xxxx-" + phone.substring(7));
		var recipients = contacts.stream().map(c -> new ContactView(c.id(), c.slot(), open(key,c.id(),"name",c.name()),
			open(key,c.id(),"relationship",c.relationship()), open(key,c.id(),"phone",c.phone()), c.version())).toList();
		String baseBody = (mode==Mode.TEST ? "[테스트] " : "") + name + "(" + identity.maskedPhone() + ")의 SafeCall 안심 메시지입니다.";
		var expiresAt = preparedAt.plusSeconds(300);
		if (material.sessionExpiresAt().isBefore(expiresAt)) expiresAt = material.sessionExpiresAt();
		if (!material.sessionExpiresAt().isAfter(clock.instant())) {
			authentication.expire(session.id());
			throw new SessionInvalidException(ErrorCode.SESSION_EXPIRED);
		}
		return new MessageComposerView(mode, recipients, identity, baseBody, TEMPLATE_VERSION, material.isLocationGranted(),
			policy.mapTemplate(), "이 화면에서는 실제 문자가 발송되지 않습니다.", preparedAt, expiresAt);
	}
	private String open(byte[] key, UUID owner, String field, byte[] cipher) {
		return new String(crypto.open(key, owner + ":" + field, cipher), StandardCharsets.UTF_8);
	}
}

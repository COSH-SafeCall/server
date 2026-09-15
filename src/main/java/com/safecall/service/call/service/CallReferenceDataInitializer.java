package com.safecall.service.call.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CallReferenceDataInitializer implements ApplicationRunner {
	private final JdbcTemplate jdbc;

	public CallReferenceDataInitializer(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments arguments) {
		jdbc.update("INSERT IGNORE INTO `scenario` (`code`,`label`,`sortOrder`) VALUES ('FOLLOWED','누군가 따라오는 것 같아요',1),('UNSAFE_TAXI','택시 안이 불안해요',2),('STRANGER_NEARBY','낯선 사람이 근처에 있어요',3),('WALKING_ALONE','혼자 귀가하기 무서워요',4)");
		jdbc.update("INSERT IGNORE INTO `counterpart` (`code`,`label`,`displayName`,`sortOrder`) VALUES ('FATHER','아빠','아빠',1),('MOTHER','엄마','엄마',2),('FRIEND','친구','친구',3)");
	}
}

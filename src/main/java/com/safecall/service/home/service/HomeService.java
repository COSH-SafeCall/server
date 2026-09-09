package com.safecall.service.home.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.safecall.service.auth.api.AuthDtos.Step;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.error.SessionInvalidException;
import com.safecall.service.home.api.HomeDtos.*;
import com.safecall.service.home.repository.HomeRepository;
import com.safecall.service.user.repository.UserRepository;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED, noRollbackFor=SessionInvalidException.class)
public class HomeService {
	// Fixed catalog deployment version; labels/order also participate in the response ETag.
	private static final int CATALOG_VERSION=2;
	private final AuthTransactions authentication;
	private final AuthRepository auth;
	private final UserRepository users;
	private final HomeRepository repository;
	private final Clock clock;
	public HomeService(AuthTransactions authentication, AuthRepository auth, UserRepository users,
		HomeRepository repository, Clock clock) {
		this.authentication=authentication; this.auth=auth; this.users=users; this.repository=repository; this.clock=clock;
	}
	public HomeView home(String access) {
		var session=authentication.session(access);
		if (session.userId()==null) return new HomeView(false,List.of("LOGIN_REQUIRED"),false,0,"LOGIN_ONLY");
		var user=auth.user(session.userId(),false);
		var reasons=new ArrayList<String>();
		if (session.onboardingStep()!=Step.COMPLETE) reasons.add("ONBOARDING_REQUIRED");
		if (user.confirmedAt()==null || user.nameCipher()==null || user.phoneCipher()==null) reasons.add("PROFILE_REQUIRED");
		// Message eligibility requires privacy consent, independently of AI call consent.
		if (!isGranted(user.id(),"PRIVACY_PROCESSING")) reasons.add("CONSENT_REQUIRED");
		int count=repository.guardianCount(user.id());
		if (count<1 || count>2) reasons.add("CONTACT_REQUIRED");
		return new HomeView(reasons.isEmpty(),List.copyOf(reasons),isGranted(user.id(),"LOCATION_PROCESSING"),count,"MEMBER");
	}
	private boolean isGranted(UUID userId, String code) {
		var document=users.documents(false).stream().filter(d -> d.code().equals(code) && d.isConsent()
			&& !d.publishedAt().isAfter(clock.instant())).findFirst().orElse(null);
		if (document==null) return false;
		var event=users.latest(userId,code);
		return event!=null && event.action().equals("GRANTED") && event.version()==document.version();
	}
	public CallOptionsView callOptions(String access) {
		// Authenticate before the controller evaluates If-None-Match, including 304 requests.
		authentication.session(access);
		var scenarios=repository.scenarios();
		var counterparts=repository.counterparts();
		if (scenarios.size()!=4 || counterparts.size()!=3) throw new IllegalStateException("Incomplete call catalog.");
		return new CallOptionsView(scenarios,counterparts,new QuickStart(1000,"FATHER"),CATALOG_VERSION);
	}
}

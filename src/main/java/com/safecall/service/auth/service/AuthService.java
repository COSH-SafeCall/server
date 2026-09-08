package com.safecall.service.auth.service;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import com.safecall.service.auth.api.AuthDtos.*;
import com.safecall.service.auth.kakao.KakaoClient;
import com.safecall.service.common.error.*;

@Service
public class AuthService {
	private final AuthTransactions transactions;
	private final KakaoClient kakao;
	public AuthService(AuthTransactions transactions, KakaoClient kakao) {
		this.transactions = transactions; this.kakao = kakao;
	}
	public AuthResponse guest(GuestRequest request, UUID key, String access) {
		return retry(() -> transactions.guest(request, key, access));
	}
	public AuthResponse kakao(KakaoRequest request, UUID key, String access) {
		// 외부 호출은 DB 잠금 전에 수행하며 응답 재생 요청에도 토큰을 다시 검증한다.
		var identity = kakao.verify(request.kakaoAccessToken());
		return retry(() -> transactions.kakao(request, identity, key, access));
	}
	public Tokens refresh(RefreshRequest request, UUID key) {
		return retry(() -> transactions.refresh(request, key));
	}
	public void logout(String access, UUID key) {
		try { retry(() -> { transactions.logout(access, key); return null; }); }
		catch (org.springframework.dao.DataAccessException exception) { throw new CustomException(ErrorCode.LOGOUT_FAILED); }
	}
	public SessionView session(String access) { return retry(() -> transactions.session(access)); }
	public OnboardingView onboarding(String access) { return retry(() -> transactions.onboarding(access)); }
	public OnboardingView advance(String access, AdvanceRequest request, UUID key) {
		return retry(() -> transactions.advance(access, request, key));
	}
	private <T> T retry(Supplier<T> work) {
		for (int attempt = 0; ; attempt++) {
			try { return work.get(); }
			catch (PessimisticLockingFailureException exception) {
				if (attempt >= 2) throw new CustomException(ErrorCode.REQUEST_IN_PROGRESS, 1);
			} catch (DuplicateKeyException exception) {
				// subject 동시 최초 생성만 재시도한다. 다른 제약은 내부 오류로 남긴다.
				String message = exception.getMostSpecificCause().getMessage();
				if (message == null || !message.contains("uqAppUser1") || attempt >= 2) throw exception;
			}
		}
	}
}

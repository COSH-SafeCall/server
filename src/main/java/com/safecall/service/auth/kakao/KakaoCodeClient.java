package com.safecall.service.auth.kakao;
public interface KakaoCodeClient {
	KakaoClient.KakaoIdentity exchange(String code,String redirectUri);
}

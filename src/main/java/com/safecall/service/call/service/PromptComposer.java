package com.safecall.service.call.service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.stereotype.Component;
import com.safecall.service.auth.repository.AuthRows.User;
import com.safecall.service.call.repository.CallRepository.Prompt;
import com.safecall.service.common.crypto.*;

@Component
public class PromptComposer {
	private final UserKeyStore keys;
	private final SecretCrypto crypto;
	public PromptComposer(UserKeyStore keys,SecretCrypto crypto) { this.keys=keys; this.crypto=crypto; }
	public record Composed(String instruction,boolean isDemographicApplied,boolean isGenderAddressApplied) {
		@Override public String toString() { return "Composed[redacted]"; }
	}
	public Composed compose(Prompt prompt,User user,Instant now) {
		String gender=null; LocalDate birth=null;
		if(user!=null) {
			byte[] key=keys.read(user.keyRef());
			if("KAKAO".equals(user.genderSource())) gender=open(key,user,"gender",user.genderCipher());
			if("KAKAO".equals(user.birthDateSource())) {
				String value=open(key,user,"birthDate",user.birthDateCipher());
				if(value!=null) birth=LocalDate.parse(value);
			}
		}
		return compose(prompt,gender,birth,now);
	}
	static Composed compose(Prompt prompt,String gender,LocalDate birth,Instant now) {
		boolean known="MALE".equals(gender)||"FEMALE".equals(gender);
		LocalDate today=LocalDate.ofInstant(now,ZoneId.of("Asia/Seoul"));
		boolean demographic=known && birth!=null && !birth.isAfter(today);
		boolean address=known && !"FRIEND".equals(prompt.counterpart());
		StringBuilder text=new StringBuilder(prompt.base()).append('\n').append(prompt.safety()).append('\n');
		// These server invariants apply to every release, including future reviewed content.
		text.append("이 통화는 AI가 연기하는 일상 대화다. 사용자의 첫 발화 전에는 말하지 않는다. 위험 상황을 먼저 언급하거나 자세한 위험 설명을 요구하지 않는다. ")
			.append("신고·문자·위치 전송을 실행했다고 주장하지 않는다. 위험도를 판단하거나 대치·추적·촬영을 유도하지 않는다. 도구를 호출하지 않는다.\n");
		if(address) text.append("사용자를 ").append("MALE".equals(gender)?"아들":"딸").append("로 부를 수 있다.\n");
		else if("FRIEND".equals(prompt.counterpart())) text.append("사용자를 너로 부른다.\n");
		else text.append("사용자의 성별을 추정하는 호칭을 쓰지 않는다.\n");
		if(demographic) text.append(prompt.demographic().replace("{age}",Integer.toString(Period.between(birth,today).getYears()))
			.replace("{gender}","MALE".equals(gender)?"남성":"여성")).append('\n');
		else text.append(prompt.guest()).append(" 나이·학교·학원 등 확인되지 않은 생활 정보를 추정하지 않는다.\n");
		return new Composed(text.toString(),demographic,address);
	}
	private String open(byte[] key,User user,String field,byte[] value) {
		return value==null?null:new String(crypto.open(key,user.id()+":"+field,value),StandardCharsets.UTF_8);
	}
}

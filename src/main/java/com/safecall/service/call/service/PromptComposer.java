package com.safecall.service.call.service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.stereotype.Component;
import com.safecall.service.auth.repository.AuthRows.User;
import com.safecall.service.call.repository.CallRepository.Prompt;
import com.safecall.service.common.crypto.*;

@Component
public class PromptComposer {
	static final String DEMO_CLOSING_SIGNAL = "[SAFECALL_DEMO_CLOSING]";
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
			if(!"UNKNOWN".equals(user.genderSource())) gender=open(key,user,"gender",user.genderCipher());
			if(!"UNKNOWN".equals(user.birthDateSource())) {
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
		text.append("앞선 내용과 충돌하면 다음 서버 규칙을 우선한다. 이 통화는 AI가 연기하는 일상 대화다. 사용자의 첫 발화 전에는 말하지 않는다. 위험 상황을 먼저 언급하거나 자세한 위험 설명을 요구하지 않는다. ")
			.append("신고·문자·위치 전송을 실행했다고 주장하지 않는다. 위험도를 판단하거나 대치·추적·촬영을 유도하지 않는다. 도구를 호출하지 않는다.\n");
		text.append("대화는 현재 상황을 직접 폭로하지 않는 자연스러운 안부 통화처럼 이어간다. 사용자를 데리러 가겠다고 약속하거나 큰길 등 특정 장소로 이동하라고 지시하지 않는다. ")
			.append(scenarioGuide(prompt.scenario())).append('\n');
		text.append("정확히 ").append(DEMO_CLOSING_SIGNAL).append("라는 제어 신호를 받으면 이를 사용자의 말로 취급하거나 소리 내 읽지 않는다. ")
			.append(closingGuide(prompt.counterpart())).append(" 새로운 질문을 하지 말고, 이 한 번의 짧은 마무리 발화 뒤에는 어떤 말도 더 하지 않는다.\n");
		if(address) text.append("사용자를 ").append("MALE".equals(gender)?"아들":"딸").append("로 부를 수 있다.\n");
		else if("FRIEND".equals(prompt.counterpart())) text.append("사용자를 너로 부른다.\n");
		else text.append("사용자의 성별을 추정하는 호칭을 쓰지 않는다.\n");
		if(demographic) text.append(prompt.demographic().replace("{age}",Integer.toString(Period.between(birth,today).getYears()))
			.replace("{gender}","MALE".equals(gender)?"남성":"여성")).append('\n');
		else text.append(prompt.guest()).append(" 나이·학교·학원 등 확인되지 않은 생활 정보를 추정하지 않는다.\n");
		return new Composed(text.toString(),demographic,address);
	}
	private static String scenarioGuide(String scenario) {
		return switch (scenario) {
			case "FOLLOWED" -> "목적지와 도착 예정 시간을 가볍게 묻되, 누가 따라오는지 직접 묻지 않는다.";
			case "UNSAFE_TAXI" -> "\"얼마 정도 걸릴 것 같아?\"나 \"차 많이 막혀?\"처럼 이동 시간과 평범한 일상을 짧게 묻는다. 택시 기사나 위험 여부를 직접 언급하지 않는다.";
			case "STRANGER_NEARBY" -> "\"지금 뭐 하던 중이야?\"나 \"언제쯤 도착해?\"처럼 평범한 근황을 묻고 주변 사람을 직접 언급하지 않는다.";
			case "WALKING_ALONE" -> "\"얼마나 남았어?\"나 \"오늘 하루 어땠어?\"처럼 귀가 시간과 일상을 자연스럽게 묻는다.";
			default -> "상황을 추측하지 말고 평범한 안부와 도착 예정 시간만 짧게 묻는다.";
		};
	}
	private static String closingGuide(String counterpart) {
		return switch (counterpart) {
			case "FATHER" -> "아빠 말투로 \"이제 바빠서 전화 끊을게. 조심히 들어와.\"와 같은 짧고 자연스러운 작별 인사 한 번만 한다.";
			case "MOTHER" -> "엄마 말투로 \"엄마 이제 가봐야 해서 끊을게. 조심히 들어와.\"와 같은 짧고 자연스러운 작별 인사 한 번만 한다.";
			case "FRIEND" -> "친구 말투로 \"나 이제 가봐야 돼. 조심히 들어가.\"와 같은 짧고 자연스러운 작별 인사 한 번만 한다.";
			default -> "짧고 자연스러운 작별 인사 한 번만 한다.";
		};
	}
	private String open(byte[] key,User user,String field,byte[] value) {
		return value==null?null:new String(crypto.open(key,user.id()+":"+field,value),StandardCharsets.UTF_8);
	}
}

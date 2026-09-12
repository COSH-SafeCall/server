package com.safecall.service.call.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.safecall.service.auth.repository.AuthRows.User;
import com.safecall.service.call.repository.CallRepository.Prompt;
import com.safecall.service.common.crypto.*;

class PromptComposerTest {
	private Prompt prompt(String counterpart) {
		return new Prompt(UUID.randomUUID(),"models/synthetic","v1beta","안전 지침","확인된 {age}세 {gender}의 일상 말투.","중립적인 대화.","가족의 일상 대화.","Puck","FOLLOWED",counterpart);
	}
	@Test void guestDoesNotInferGenderOrAge() {
		var result=PromptComposer.compose(prompt("FATHER"),null,null,Instant.parse("2026-09-09T15:00:00Z"));
		assertThat(result.isDemographicApplied()).isFalse(); assertThat(result.isGenderAddressApplied()).isFalse();
		assertThat(result.instruction()).contains("중립적인 대화","첫 발화 전에는 말하지 않는다","추정하지 않는다").doesNotContain("아들로","딸로");
	}
	@Test void birthdayUsesSeoulAndOmitsBirthDate() {
		var result=PromptComposer.compose(prompt("MOTHER"),"MALE",LocalDate.of(2000,9,10),Instant.parse("2026-09-09T15:00:00Z"));
		assertThat(result.isDemographicApplied()).isTrue(); assertThat(result.isGenderAddressApplied()).isTrue();
		assertThat(result.instruction()).contains("26세 남성","아들로").doesNotContain("2000","09-10");
	}
	@Test void genderOnlyAddsAddressWithoutAgeTone() {
		var result=PromptComposer.compose(prompt("FATHER"),"FEMALE",null,Instant.now());
		assertThat(result.isDemographicApplied()).isFalse(); assertThat(result.instruction()).contains("딸로","중립적인 대화");
	}
	@Test void friendUsesNeutralAddressEvenWithKnownDemographics() {
		var result=PromptComposer.compose(prompt("FRIEND"),"FEMALE",LocalDate.of(2000,1,1),Instant.now());
		assertThat(result.isGenderAddressApplied()).isFalse(); assertThat(result.instruction()).contains("너로 부른다").doesNotContain("딸로");
	}
	@Test void userConfirmedValuesAreNeverDecryptedForGemini() {
		UserKeyStore keys=mock(UserKeyStore.class); SecretCrypto crypto=mock(SecretCrypto.class);
		var user=new User(UUID.randomUUID(),"ACTIVE","synthetic-key",new byte[]{1},new byte[]{2},new byte[]{3},new byte[]{4},"USER_CONFIRMED","USER_CONFIRMED",Instant.now(),1);
		when(keys.read(anyString())).thenReturn(new byte[32]);
		var result=new PromptComposer(keys,crypto).compose(prompt("FATHER"),user,Instant.now());
		assertThat(result.isDemographicApplied()).isFalse(); assertThat(result.isGenderAddressApplied()).isFalse(); verifyNoInteractions(crypto);
	}
}

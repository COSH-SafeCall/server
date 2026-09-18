package com.safecall.service.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.auth.repository.AuthRows.Session;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.history.api.HistoryDtos.DeletionRequest;
import com.safecall.service.history.api.HistoryDtos.DeletionScope;
import com.safecall.service.history.repository.HistoryRepository;
import com.safecall.service.history.repository.HistoryRepository.Job;
import com.safecall.service.user.api.UserDtos.DeletionView;
import com.safecall.service.user.repository.UserRepository;

class HistoryServiceTest {
	@Test void accountDeletionRevokesEveryActiveMemberSessionImmediately() {
		var authentication=mock(AuthTransactions.class);var auth=mock(AuthRepository.class);var users=mock(UserRepository.class);
		var repository=mock(HistoryRepository.class);var crypto=mock(SecretCrypto.class);var mapper=mock(JsonMapper.class);
		var now=Instant.parse("2026-09-19T00:00:00Z");var userId=UUID.randomUUID();var currentId=UUID.randomUUID();var otherId=UUID.randomUUID();
		var current=new Session(currentId,new byte[32],new byte[32],userId,"MEMBER","ACTIVE",now.minusSeconds(60),now.plusSeconds(3600));
		var other=new Session(otherId,new byte[32],new byte[32],userId,"MEMBER","ACTIVE",now.minusSeconds(60),now.plusSeconds(3600));
		when(authentication.member("cookie",true)).thenReturn(current);
		when(crypto.idempotency(anyString(),eq(userId),anyString(),anyString())).thenReturn(new byte[32]);
		when(crypto.hash(anyString(),anyString())).thenReturn(new byte[32]);
		when(crypto.randomToken()).thenReturn("a".repeat(43));
		when(crypto.sealResponse(anyString(),any())).thenReturn(new byte[]{1});
		when(mapper.writeValueAsString(any())).thenReturn("{}");
		when(mapper.writeValueAsBytes(any())).thenReturn(new byte[]{1});
		when(users.sessions(userId)).thenReturn(List.of(currentId,otherId));
		when(auth.lockSession(currentId)).thenReturn(current);
		when(auth.lockSession(otherId)).thenReturn(other);
		when(repository.job(any())).thenAnswer(invocation -> {
			UUID id=invocation.getArgument(0);
			return new Job(id,userId,new DeletionView(id,"ACCOUNT","PENDING",now,now.plusSeconds(86400),null,null),new byte[32],now.plusSeconds(2592000));
		});

		var service=new HistoryService(authentication,auth,users,repository,mock(HistoryCursor.class),crypto,mapper,Clock.fixed(now,ZoneOffset.UTC));
		var result=service.request("cookie",new DeletionRequest(DeletionScope.ACCOUNT,true),UUID.randomUUID());

		assertThat(result.view().status()).isEqualTo("PENDING");
		verify(auth).endSession(eq(current),eq(now),eq("REVOKED"),eq("DATA_DELETION"),any());
		verify(auth).endSession(eq(other),eq(now),eq("REVOKED"),eq("DATA_DELETION"),any());
	}
}

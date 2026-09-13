package com.safecall.service.history.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.auth.repository.AuthRepository;
import com.safecall.service.auth.service.AuthTransactions;
import com.safecall.service.common.crypto.SecretCrypto;
import com.safecall.service.common.error.*;
import com.safecall.service.history.api.HistoryDtos.*;
import com.safecall.service.history.repository.HistoryRepository;
import com.safecall.service.user.api.UserDtos.*;
import com.safecall.service.user.repository.UserRepository;
import com.safecall.service.user.service.UserTransactions.WithdrawalResult;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED,noRollbackFor=SessionInvalidException.class)
public class HistoryService {
	private final AuthTransactions authentication;
	private final AuthRepository auth;
	private final UserRepository users;
	private final HistoryRepository repository;
	private final HistoryCursor cursors;
	private final SecretCrypto crypto;
	private final JsonMapper mapper;
	private final Clock clock;
	public HistoryService(AuthTransactions authentication,AuthRepository auth,UserRepository users,HistoryRepository repository,
		HistoryCursor cursors,SecretCrypto crypto,JsonMapper mapper,Clock clock) {
		this.authentication=authentication;this.auth=auth;this.users=users;this.repository=repository;
		this.cursors=cursors;this.crypto=crypto;this.mapper=mapper;this.clock=clock;
	}
	public UsageHistoryView history(String cookie,int limit,String cursor) {
		var session=authentication.member(cookie,false);
		var rows=repository.history(session.userId(),limit+1,cursor==null?null:cursors.decode(session.userId(),cursor));
		boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
		String next=more?cursors.encode(session.userId(),new HistoryCursor.Position(items.getLast().createdAt(),items.getLast().id())):null;
		return new UsageHistoryView(items,next);
	}
	public WithdrawalResult request(String cookie,DeletionRequest input,UUID key,boolean isKakaoInApp) {
		var session=authentication.member(cookie,true);var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
		byte[] scope=crypto.idempotency("USER",session.userId(),"COLLECTION","data-deletions");
		byte[] hash=crypto.hash("REQUEST",mapper.writeValueAsString(input));
		var replay=auth.replay(scope,"DATA_DELETION",key);
		if (replay!=null) {
			if (!crypto.isEqual(hash,replay.requestHash())) throw new CustomException(ErrorCode.IDEMPOTENCY_CONFLICT);
			if (!"DONE".equals(replay.status())) throw new CustomException(ErrorCode.REQUEST_IN_PROGRESS,1);
			if (replay.responseCipher()==null || replay.responseExpiresAt()==null || !replay.responseExpiresAt().isAfter(now))
				throw new CustomException(ErrorCode.DELETION_RECEIPT_EXPIRED);
			var saved=mapper.readValue(crypto.openResponse(replay.id().toString(),replay.responseCipher()),DeletionReceipt.class);
			var job=repository.job(saved.id());
			if (job==null || !crypto.isEqual(job.receiptHash(),crypto.hash("DELETION_RECEIPT",saved.receiptToken())))
				throw new CustomException(ErrorCode.DELETION_RECEIPT_EXPIRED);
			return new WithdrawalResult(job.view(),saved.receiptToken(),saved.receiptExpiresAt());
		}
		if (input.scope()==DeletionScope.ACCOUNT) {
			if (isKakaoInApp) throw new CustomException(ErrorCode.REAUTHENTICATION_REQUIRED);
			authentication.requireSensitive(session);
		} else {
			if (users.pending(session.userId(),"ACCOUNT")) throw new CustomException(ErrorCode.ACCOUNT_DELETION_PENDING);
			if (repository.hasOpenCall(session.userId())) throw new CustomException(ErrorCode.CALL_ALREADY_OPEN);
		}
		String target=input.scope().name(); UUID id=repository.pending(session.userId(),target);
		// 재생 구간의 접수증은 재사용하여 동시 접수나 다른 키가 먼저 발급한 쿠키를 무효화하지 않는다.
		String token=crypto.randomToken();Instant expiry=now.plusSeconds(2592000);
		if (id!=null) {
			var previous=repository.receiptReplay(id,now);
			if (previous!=null) {
				var root=mapper.readTree(crypto.openResponse(previous.id().toString(),previous.cipher()));
				var receipt=mapper.readValue((root.has("deletion")?root.path("deletion"):root).toString(),DeletionReceipt.class);
				token=receipt.receiptToken();expiry=receipt.receiptExpiresAt();
			}
		}
		if (id==null) {
			id=UUID.randomUUID();
			users.deletion(session.userId(),new DeletionReceipt(id,target,"PENDING",token,now.plusSeconds(86400),expiry),crypto.hash("DELETION_RECEIPT",token),now);
			if (input.scope()==DeletionScope.ACCOUNT) for(UUID sessionId:users.sessions(session.userId())) {
				var locked=auth.lockSession(sessionId);
				if (locked!=null) auth.endCalls(locked,now,"DATA_DELETION",crypto.hash("SERVER_EVENT","DATA_DELETION"));
			}
		} else repository.receipt(id,crypto.hash("DELETION_RECEIPT",token),expiry);
		var job=repository.job(id);var receipt=new DeletionReceipt(id,target,job.view().status(),token,job.view().dueAt(),expiry);
		UUID replayId=UUID.randomUUID();
		auth.saveReplay(replayId,session,scope,"DATA_DELETION",key,hash,id,
			crypto.sealResponse(replayId.toString(),mapper.writeValueAsBytes(receipt)),now.plusSeconds(60),now);
		return new WithdrawalResult(job.view(),token,expiry);
	}
	public DeletionView status(String cookie,String receipt,UUID id) {
		// 잘못된 세션이 있어도 유효한 접수증은 독립적으로 사용할 수 있다.
		var job=repository.job(id);
		if (job==null) throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
		if (receipt!=null && receipt.matches("[A-Za-z0-9_-]{43}") && job.receiptExpiresAt().isAfter(clock.instant())
			&& crypto.isEqual(job.receiptHash(),crypto.hash("DELETION_RECEIPT",receipt))) return job.view();
		if (cookie!=null && cookie.matches("[A-Za-z0-9_-]{43}")) {
			var session=auth.byCookie(crypto.hash("WEB_SESSION",cookie));
			if (session!=null && session.kind().equals("KAKAO") && session.status().equals("ACTIVE")
				&& session.expiresAt().isAfter(clock.instant()) && session.userId()!=null && session.userId().equals(job.userId()))
				return job.view();
		}
		throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
	}
}

package com.safecall.service.history.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;

public final class HistoryDtos {
	private HistoryDtos() {}
	public enum DeletionScope { ACCOUNT, USAGE_HISTORY }
	public record DeletionRequest(@NotNull DeletionScope scope, @NotNull @AssertTrue Boolean isConfirmed) {}
	public record UsageHistoryItem(UUID id, String state, String startMode, String scenarioCode, String counterpartCode,
		String displayName, Instant createdAt, Instant answeredAt, Instant endedAt, String endReason) {}
	public record UsageHistoryView(List<UsageHistoryItem> items, String nextCursor) {}
}

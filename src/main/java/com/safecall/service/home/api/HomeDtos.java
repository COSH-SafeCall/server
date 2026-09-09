package com.safecall.service.home.api;

import java.util.List;

public final class HomeDtos {
	private HomeDtos() {}
	public record HomeView(boolean isMessageComposeEligible, List<String> messageBlockReasons,
		boolean isLocationConsentGranted, int guardianCount, String settingsMode) {}
	public record ScenarioView(String code, String label, String quickDirection) {}
	public record CounterpartView(String code, String label, String displayName) {}
	public record QuickStart(int holdMs, String counterpartCode) {}
	public record CallOptionsView(List<ScenarioView> scenarios, List<CounterpartView> counterparts,
		QuickStart quickStart, int catalogVersion) {}
}

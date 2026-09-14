package com.safecall.service.common.config;

import java.util.List;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.tags.Tag;

/** One place for Swagger groups and operation order; HTTP contracts are unchanged. */
final class OpenApiDisplayOrder {

	private record Group(String name, String description, List<String> operations) {}
	private static final List<Group> GROUPS = List.of(
		new Group("01. 인증·세션", "A05로 시작하고 게스트 진입·로그인 후 다시 A05를 조회합니다. 로그아웃은 테스트 마지막에 실행합니다.", List.of("A05", "A01", "A02", "A02_CALLBACK", "A04")),
		new Group("02. 온보딩", "A06의 현재 step/version을 따릅니다. 회원 PROFILE·CONTACTS 단계에서는 아래 프로필·연락망 API를 먼저 사용합니다.", List.of("A06", "A07")),
		new Group("03. 사용자 정보", "프로필 조회 후 현재 version으로 확인·수정합니다.", List.of("U01", "U02")),
		new Group("04. 동의", "동의 문구는 프론트엔드 정적 콘텐츠이며 서버는 고정 버전 1의 선택과 철회 이력을 관리합니다.", List.of("U04", "U05", "U06")),
		new Group("05. 비상 연락망", "조회 → 등록 → 수정 → 삭제. 메시지 테스트에는 보호자 1명 이상이 필요합니다.", List.of("U07", "U08", "U09", "U10")),
		new Group("06. 사용자 설정", "조회 후 현재 version으로 알림 방식을 수정합니다.", List.of("U11", "U12")),
		new Group("07. 홈·통화 선택지", "현재 기능 자격과 통화 선택지를 확인합니다.", List.of("H01", "H02")),
		new Group("08. 안심 메시지", "MESSAGE_TEST에서는 TEST, 온보딩 완료 후에는 SAFETY를 사용합니다. 실제 SMS 발송 API가 아닙니다.", List.of("M01")),
		new Group("09. AI 안심 통화", "생성 직후 C05를 5초마다 유지하며 C03을 조회합니다. 실제 음성·재개에는 Live 클라이언트가 필요합니다.", List.of("C01", "C02", "C03", "C04", "C05", "C06", "C07")),
		new Group("10. 운영 관측", "새 eventId 접수 후 같은 본문 재전송으로 중복 처리를 확인합니다.", List.of("O01")),
		new Group("11. 이용 기록·데이터 삭제", "통화 종료 후 이력을 확인합니다. ACCOUNT 삭제는 테스트 계정에서 다른 검증을 마친 뒤 실행합니다.", List.of("R01", "R02", "R03"))
	);

	private OpenApiDisplayOrder() {}

	static List<Tag> tags() {
		return GROUPS.stream().map(group -> new Tag().name(group.name()).description(group.description())).toList();
	}

	static void apply(Operation operation) {
		String id = operation.getOperationId();
		for (Group group : GROUPS) {
			int order = group.operations().indexOf(id);
			if (order < 0) continue;
			operation.setTags(List.of(group.name()));
			operation.addExtension("x-display-order", order);
			String summary = operation.getSummary();
			if (summary != null && !summary.startsWith(id + " · ")) operation.setSummary(id + " · " + summary);
			return;
		}
	}
}

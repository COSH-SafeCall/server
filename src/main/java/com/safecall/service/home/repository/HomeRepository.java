package com.safecall.service.home.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.safecall.service.auth.repository.AuthRepository.bin;
import com.safecall.service.home.api.HomeDtos.*;

@Repository
public class HomeRepository {
	private final JdbcTemplate jdbc;
	public HomeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
	public int guardianCount(UUID userId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM `emergencyContact` WHERE `userId`=?",Integer.class,bin(userId));
	}
	public List<ScenarioView> scenarios() {
		return jdbc.query("SELECT `code`,`label`,`sortOrder` FROM `scenario` ORDER BY `sortOrder`", (r,n) ->
			new ScenarioView(r.getString("code"),r.getString("label"),switch(r.getInt("sortOrder")) {
				case 1 -> "UP"; case 2 -> "RIGHT"; case 3 -> "DOWN"; case 4 -> "LEFT";
				default -> throw new IllegalStateException("Invalid scenario order.");
			}));
	}
	public List<CounterpartView> counterparts() {
		return jdbc.query("SELECT `code`,`label`,`displayName` FROM `counterpart` ORDER BY `sortOrder`", (r,n) ->
			new CounterpartView(r.getString("code"),r.getString("label"),r.getString("displayName")));
	}
}

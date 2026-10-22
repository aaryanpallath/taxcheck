package com.aaryanpallath.taxcheck.rates;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RateRepository {
	private final JdbcTemplate jdbc;

	public RateRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static RatePeriod map(ResultSet rs, int i) throws SQLException {
		return new RatePeriod(rs.getString("location_code").trim(), rs.getString("location_name"),
				rs.getBigDecimal("state_rate"), rs.getBigDecimal("local_rate"), rs.getBigDecimal("rta_rate"),
				rs.getBigDecimal("combined_rate"), rs.getDate("effective_from").toLocalDate(),
				rs.getDate("effective_to").toLocalDate());
	}

	public int count() {
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM rate_period", Integer.class);
		return n == null ? 0 : n;
	}

	public void insertAll(List<RatePeriod> ps) {
		jdbc.batchUpdate("""
				INSERT INTO rate_period (location_code, location_name, state_rate, local_rate, rta_rate, combined_rate,
				                         effective_from, effective_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", ps, 500,
				(st, p) -> {
					st.setString(1, p.code());
					st.setString(2, p.name());
					st.setBigDecimal(3, p.state());
					st.setBigDecimal(4, p.local());
					st.setBigDecimal(5, p.rta());
					st.setBigDecimal(6, p.combined());
					st.setDate(7, Date.valueOf(p.from()));
					st.setDate(8, Date.valueOf(p.to()));
				});
	}

	/** The rate in force at a location on a date, if the location existed then. */
	public Optional<RatePeriod> rateOn(String code, LocalDate date) {
		return jdbc.query("""
				SELECT * FROM rate_period WHERE location_code = ? AND ? BETWEEN effective_from AND effective_to""",
				RateRepository::map, code, Date.valueOf(date)).stream().findFirst();
	}

	public List<RatePeriod> history(String code) {
		return jdbc.query("SELECT * FROM rate_period WHERE location_code = ? ORDER BY effective_from",
				RateRepository::map, code);
	}

	/** Every location's rate on a date: used to find which jurisdiction a wrong rate came from. */
	public List<RatePeriod> allOn(LocalDate date) {
		return jdbc.query("SELECT * FROM rate_period WHERE ? BETWEEN effective_from AND effective_to ORDER BY location_code",
				RateRepository::map, Date.valueOf(date));
	}

	public List<RatePeriod> search(String text) {
		return jdbc.query("""
				SELECT * FROM rate_period WHERE (UPPER(location_name) LIKE ? OR location_code = ?)
				  AND effective_to = (SELECT MAX(effective_to) FROM rate_period r2 WHERE r2.location_code = rate_period.location_code)
				ORDER BY location_name LIMIT 50""", RateRepository::map, "%" + text.toUpperCase() + "%", text);
	}

	/** Rate changes: (code, name, date of change, old rate, new rate), most recent first. */
	public List<RateChange> changes() {
		return jdbc.query("""
				SELECT a.location_code, b.location_name, b.effective_from, a.combined_rate AS old_rate, b.combined_rate AS new_rate
				FROM rate_period a JOIN rate_period b
				  ON a.location_code = b.location_code AND DATEADD('DAY', 1, a.effective_to) = b.effective_from
				WHERE a.combined_rate <> b.combined_rate
				ORDER BY b.effective_from DESC, b.location_name""",
				(rs, i) -> new RateChange(rs.getString(1).trim(), rs.getString(2), rs.getDate(3).toLocalDate(),
						rs.getBigDecimal(4), rs.getBigDecimal(5)));
	}

	public record RateChange(String code, String name, LocalDate on, java.math.BigDecimal oldRate, java.math.BigDecimal newRate) {
	}
}

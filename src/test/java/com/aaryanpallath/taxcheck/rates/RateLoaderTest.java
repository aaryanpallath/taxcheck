package com.aaryanpallath.taxcheck.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;


class RateLoaderTest {
	private static RatePeriod q(String code, String rate, String from, String to) {
		BigDecimal r = new BigDecimal(rate);
		return new RatePeriod(code, "X", new BigDecimal("0.065"), r.subtract(new BigDecimal("0.065")), BigDecimal.ZERO, r,
				LocalDate.parse(from), LocalDate.parse(to));
	}

	@Test
	void codesArePaddedBackToFourDigits() {
		assertThat(RateLoader.normalizeCode("100")).isEqualTo("0100");
		assertThat(RateLoader.normalizeCode(" 1726 ")).isEqualTo("1726");
		assertThatThrownBy(() -> RateLoader.normalizeCode("17A6")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RateLoader.normalizeCode("12345")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parsesAFileWithAByteOrderMarkAndAShortCode() throws Exception {
		String csv = "﻿Name,Code,State,Local,RTA,Rate,Effective Date,Expiration Date\n"
				+ "ADAMS COUNTY,100,0.065,0.015,0,0.08,20250101,20250331\n" + "\"SEATTLE, CITY OF\",1726,0.065,0.0385,0,0.1035,20250101,20250331\n";
		List<RatePeriod> rows = RateLoader.parse(new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "Rates_25Q1.csv";
			}
		});
		assertThat(rows).extracting(RatePeriod::code).containsExactly("0100", "1726");
		assertThat(rows.get(1).name()).isEqualTo("SEATTLE, CITY OF");
		assertThat(rows.get(0).to()).isEqualTo(LocalDate.of(2025, 3, 31));
	}

	@Test
	void mergesUnchangedQuartersAndSplitsOnAChangeOrAGap() {
		List<RatePeriod> merged = RateLoader.merge(List.of(
				q("1726", "0.1035", "2025-01-01", "2025-03-31"), q("1726", "0.1035", "2025-04-01", "2025-06-30"),
				q("1726", "0.1055", "2025-07-01", "2025-09-30"),
				q("0100", "0.08", "2025-01-01", "2025-03-31"), q("0100", "0.08", "2025-07-01", "2025-09-30")));
		assertThat(merged).extracting(p -> p.code() + " " + p.from() + ".." + p.to()).containsExactly(
				"0100 2025-01-01..2025-03-31", "0100 2025-07-01..2025-09-30", // a missing quarter is a gap, not a merge
				"1726 2025-01-01..2025-06-30", "1726 2025-07-01..2025-09-30");
	}
}

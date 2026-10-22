package com.aaryanpallath.taxcheck.rates;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads the Washington Department of Revenue's quarterly rate files (one CSV per quarter, a row per location code)
 * and merges consecutive quarters with the same rates into one period, so the database holds each location's rate
 * history: about 460 locations, 12 quarters.
 */
@Component
@Order(1)
public class RateLoader implements ApplicationRunner {
	private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
	private final RateRepository repo;

	public RateLoader(RateRepository repo) {
		this.repo = repo;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		if (repo.count() > 0) return;
		Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:rates/Rates_*.csv");
		Arrays.sort(files, Comparator.comparing(Resource::getFilename));
		List<RatePeriod> rows = new ArrayList<>();
		for (Resource f : files) rows.addAll(parse(f));
		repo.insertAll(merge(rows));
	}

	/** One quarter's file. The files start with a UTF-8 byte order mark, which would otherwise end up in the first header. */
	static List<RatePeriod> parse(Resource file) throws IOException {
		List<RatePeriod> out = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String header = r.readLine();
			if (header == null) return out;
			header = header.replace("﻿", "");
			if (!header.startsWith("Name,Code,State,Local,RTA,Rate,Effective Date,Expiration Date"))
				throw new IOException("unexpected header in " + file.getFilename() + ": " + header);
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				String[] c = splitCsv(line);
				out.add(new RatePeriod(normalizeCode(c[1]), c[0].trim(), new BigDecimal(c[2]), new BigDecimal(c[3]),
						new BigDecimal(c[4]), new BigDecimal(c[5]), LocalDate.parse(c[6], YMD), LocalDate.parse(c[7], YMD)));
			}
		}
		return out;
	}

	/**
	 * Location codes are four digits, but the Q1 2025 and Q3 2026 files dropped the leading zero from the 54 codes
	 * that start with one ("100" for Adams County's "0100"). Read as-is, those locations split in two and lose half a
	 * year of history; padding restores them. Spreadsheets strip the zero the same way, so uploads are padded too.
	 */
	public static String normalizeCode(String code) {
		String c = code.trim();
		if (!c.matches("\\d{1,4}")) throw new IllegalArgumentException("location code must be 1-4 digits: " + code);
		return "0".repeat(4 - c.length()) + c;
	}

	/** Comma split that respects double quotes (a few location names contain commas). */
	static String[] splitCsv(String line) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (char ch : line.toCharArray()) {
			if (ch == '"') quoted = !quoted;
			else if (ch == ',' && !quoted) {
				parts.add(cur.toString());
				cur.setLength(0);
			} else cur.append(ch);
		}
		parts.add(cur.toString());
		return parts.toArray(String[]::new);
	}

	/** Merge back-to-back quarters with identical rates; a gap or a change starts a new period. */
	static List<RatePeriod> merge(List<RatePeriod> rows) {
		Map<String, List<RatePeriod>> byCode = new LinkedHashMap<>();
		rows.stream().sorted(Comparator.comparing(RatePeriod::code).thenComparing(RatePeriod::from))
				.forEach(p -> byCode.computeIfAbsent(p.code(), k -> new ArrayList<>()).add(p));
		List<RatePeriod> out = new ArrayList<>();
		for (List<RatePeriod> ps : byCode.values()) {
			RatePeriod cur = null;
			for (RatePeriod p : ps) {
				if (cur != null && cur.sameRates(p) && cur.to().plusDays(1).equals(p.from()))
					cur = new RatePeriod(cur.code(), p.name(), cur.state(), cur.local(), cur.rta(), cur.combined(), cur.from(), p.to());
				else {
					if (cur != null) out.add(cur);
					cur = p;
				}
			}
			out.add(cur);
		}
		return out;
	}
}

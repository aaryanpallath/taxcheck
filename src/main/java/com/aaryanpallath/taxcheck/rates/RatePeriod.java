package com.aaryanpallath.taxcheck.rates;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A location's combined sales tax rate over a span of days (both ends inclusive). */
public record RatePeriod(String code, String name, BigDecimal state, BigDecimal local, BigDecimal rta,
		BigDecimal combined, LocalDate from, LocalDate to) {

	public boolean covers(LocalDate d) {
		return !d.isBefore(from) && !d.isAfter(to);
	}

	public boolean sameRates(RatePeriod o) {
		return state.compareTo(o.state) == 0 && local.compareTo(o.local) == 0 && rta.compareTo(o.rta) == 0;
	}
}

package com.aaryanpallath.taxcheck.review;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.aaryanpallath.taxcheck.tax.Category;

/** A purchase invoice: who sold it, where it was delivered (which decides the rate in Washington), and the tax charged. */
public record Invoice(String id, String vendor, String vendorLocation, String shipTo, LocalDate date, List<Line> lines,
		BigDecimal taxCharged) {

	public record Line(Category category, BigDecimal amount) {
	}

	public BigDecimal subtotal() {
		return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}

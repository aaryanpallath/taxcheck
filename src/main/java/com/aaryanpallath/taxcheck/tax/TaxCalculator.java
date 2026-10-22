package com.aaryanpallath.taxcheck.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.aaryanpallath.taxcheck.review.Invoice;
import com.aaryanpallath.taxcheck.review.Invoice.Line;

/** Tax on an invoice: the taxable lines' total times the combined rate, rounded once to the cent (half up). */
public final class TaxCalculator {
	private TaxCalculator() {
	}

	public static BigDecimal cents(BigDecimal v) {
		return v.setScale(2, RoundingMode.HALF_UP);
	}

	public static BigDecimal taxableBase(Invoice inv) {
		return inv.lines().stream().filter(l -> l.category().taxable).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	public static BigDecimal tax(BigDecimal base, BigDecimal rate) {
		return cents(base.multiply(rate));
	}

	public static BigDecimal expected(Invoice inv, BigDecimal rate) {
		return tax(taxableBase(inv), rate);
	}

	/** What a vendor gets by rounding each taxable line separately and adding: off by a cent or two from {@link #expected}. */
	public static BigDecimal perLine(List<Line> lines, BigDecimal rate) {
		return lines.stream().filter(l -> l.category().taxable).map(l -> cents(l.amount().multiply(rate)))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}

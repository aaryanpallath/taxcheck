package com.aaryanpallath.taxcheck.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.aaryanpallath.taxcheck.rates.RatePeriod;
import com.aaryanpallath.taxcheck.rates.RateRepository;
import com.aaryanpallath.taxcheck.tax.Category;
import com.aaryanpallath.taxcheck.tax.TaxCalculator;

/**
 * Synthetic purchase invoices for a manufacturer with eight Washington facilities, priced with the real rate
 * history, with known mistakes planted: the kinds an auditor finds in vendor invoices. Each invoice carries the
 * mistake that was planted, so the reviewer's diagnosis can be scored. A planted mistake that happens to produce
 * the right tax (a stale rate where the rate didn't change, say) is labeled correct.
 */
public final class SampleInvoices {
	// delivery locations: real location codes for cities with their own rates
	public static final List<String> FACILITIES = List.of("1726", "3210", "2717", "3105", "3701", "1704", "3913", "1715");
	private static final String[] VENDORS = {"Cascade Office Supply", "Puget Industrial", "Evergreen Computers",
			"Rainier Software", "Columbia Machine Works", "Olympic Distributors", "Pacific Parts Co", "Summit Tech"};

	private static final List<Integer> PER_LINE_VENDORS = List.of(1, 5);

	public record Labeled(Invoice invoice, Cause planted) {
	}

	/** The tax a vendor charges at a rate: rounded on the total, or line by line; on taxable lines, or on all of them. */
	static BigDecimal charge(List<Invoice.Line> lines, BigDecimal rate, boolean perLine, boolean taxExemptToo) {
		List<Invoice.Line> taxed = taxExemptToo ? lines : lines.stream().filter(l -> l.category().taxable).toList();
		if (perLine) return taxed.stream().map(l -> TaxCalculator.cents(l.amount().multiply(rate))).reduce(BigDecimal.ZERO, BigDecimal::add);
		return TaxCalculator.tax(taxed.stream().map(Invoice.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add), rate);
	}

	private SampleInvoices() {
	}

	public static List<Labeled> generate(RateRepository rates, int n, long seed) {
		Random rng = new Random(seed);
		LocalDate start = LocalDate.of(2024, 1, 1), end = LocalDate.of(2026, 10, 20);
		List<Labeled> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			String shipTo = FACILITIES.get(rng.nextInt(FACILITIES.size()));
			int pick = rng.nextInt(100);
			// UNEXPLAINED here means two mistakes at once (a stale rate and tax on an exempt line): no single cause fits
			Cause plan = pick < 75 ? Cause.CORRECT : pick < 81 ? Cause.STALE_RATE : pick < 86 ? Cause.ORIGIN_RATE
					: pick < 90 ? Cause.OTHER_JURISDICTION : pick < 94 ? Cause.TAX_ON_EXEMPT : pick < 97 ? Cause.NO_TAX
					: Cause.UNEXPLAINED;
			int vendor = rng.nextInt(VENDORS.length);
			boolean perLineVendor = PER_LINE_VENDORS.contains(vendor); // these vendors' systems round tax line by line
			LocalDate date = start.plusDays(rng.nextInt((int) ChronoUnit.DAYS.between(start, end) + 1));
			RatePeriod before = null;
			if (plan == Cause.STALE_RATE || plan == Cause.UNEXPLAINED) { // a stale rate only happens soon after a change
				List<RatePeriod> h = rates.history(shipTo);
				List<Integer> changes = new ArrayList<>();
				for (int k = 1; k < h.size(); k++) if (h.get(k).combined().compareTo(h.get(k - 1).combined()) != 0) changes.add(k);
				if (changes.isEmpty()) plan = Cause.CORRECT;
				else {
					int k = changes.get(rng.nextInt(changes.size()));
					before = h.get(k - 1);
					date = h.get(k).from().plusDays(rng.nextInt(60));
				}
			}
			// vendors anywhere in the state, chosen among locations that existed on the invoice date (some were
			// created or retired during these three years)
			List<RatePeriod> open = rates.allOn(date);
			String vendorLoc = open.get(rng.nextInt(open.size())).code();
			List<Invoice.Line> lines = new ArrayList<>();
			int count = 1 + rng.nextInt(4);
			for (int l = 0; l < count; l++) {
				Category cat = rng.nextInt(10) < 7 ? List.of(Category.OFFICE_SUPPLIES, Category.COMPUTER_HARDWARE, Category.PREWRITTEN_SOFTWARE).get(rng.nextInt(3))
						: List.of(Category.RESALE_INVENTORY, Category.MANUFACTURING_EQUIPMENT).get(rng.nextInt(2));
				lines.add(new Invoice.Line(cat, BigDecimal.valueOf(500 + rng.nextInt(2_000_000), 2)));
			}
			if ((plan == Cause.TAX_ON_EXEMPT || plan == Cause.UNEXPLAINED) && lines.stream().allMatch(l -> l.category().taxable))
				lines.add(new Invoice.Line(Category.MANUFACTURING_EQUIPMENT, BigDecimal.valueOf(100_000 + rng.nextInt(5_000_000), 2)));
			BigDecimal rate = rates.rateOn(shipTo, date).orElseThrow().combined();
			Invoice draft = new Invoice("INV-" + (10001 + i), VENDORS[vendor], vendorLoc, shipTo, date, lines, BigDecimal.ZERO);
			BigDecimal base = TaxCalculator.taxableBase(draft);
			RatePeriod elsewhere = open.get(rng.nextInt(open.size())); // a wrong delivery address on the vendor's file
			BigDecimal charged = switch (plan) {
				case STALE_RATE -> charge(lines, before.combined(), perLineVendor, false);
				case ORIGIN_RATE -> charge(lines, rates.rateOn(vendorLoc, date).orElseThrow().combined(), perLineVendor, false);
				case OTHER_JURISDICTION -> charge(lines, elsewhere.combined(), perLineVendor, false);
				case TAX_ON_EXEMPT -> charge(lines, rate, perLineVendor, true);
				case NO_TAX -> BigDecimal.ZERO;
				case UNEXPLAINED -> charge(lines, before.combined(), perLineVendor, true);
				default -> charge(lines, rate, perLineVendor, false);
			};
			BigDecimal right = TaxCalculator.tax(base, rate);
			Cause label = charged.compareTo(right) == 0 ? Cause.CORRECT
					: plan == Cause.CORRECT ? Cause.ROUNDING // a per-line vendor that got everything else right
					: plan;
			out.add(new Labeled(new Invoice(draft.id(), draft.vendor(), vendorLoc, shipTo, date, lines, charged.setScale(2, RoundingMode.HALF_UP)), label));
		}
		return out;
	}
}

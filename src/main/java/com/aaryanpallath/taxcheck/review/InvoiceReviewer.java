package com.aaryanpallath.taxcheck.review;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aaryanpallath.taxcheck.rates.RatePeriod;
import com.aaryanpallath.taxcheck.rates.RateRepository;
import com.aaryanpallath.taxcheck.tax.TaxCalculator;

/**
 * Reverse audit of purchase invoices. For each invoice: the tax that should have been charged (the delivery
 * location's rate on the invoice date, on taxable lines only), the difference from what was charged, and the most
 * likely root cause, found by testing which mistake reproduces the charged amount to the cent.
 */
@Service
public class InvoiceReviewer {
	private static final BigDecimal CENT = new BigDecimal("0.01");
	private static final int STALE_WINDOW_DAYS = 120; // vendors that miss a rate change usually catch up within a quarter
	private final RateRepository rates;

	public InvoiceReviewer(RateRepository rates) {
		this.rates = rates;
	}

	public List<Finding> review(List<Invoice> invoices) {
		Map<LocalDate, List<RatePeriod>> allOnDate = new LinkedHashMap<>();
		List<Finding> out = new ArrayList<>();
		for (Invoice inv : invoices) out.add(review(inv, d -> allOnDate.computeIfAbsent(d, rates::allOn)));
		return out;
	}

	public Finding review(Invoice inv) {
		return review(inv, rates::allOn);
	}

	private Finding review(Invoice inv, java.util.function.Function<LocalDate, List<RatePeriod>> allOn) {
		Optional<RatePeriod> p = rates.rateOn(inv.shipTo(), inv.date());
		if (p.isEmpty())
			return new Finding(inv, "?", null, null, BigDecimal.ZERO, Cause.UNKNOWN_LOCATION,
					"No Washington location " + inv.shipTo() + " on " + inv.date() + "; check the delivery address.", List.of());
		RatePeriod period = p.get();
		BigDecimal rate = period.combined();
		BigDecimal base = TaxCalculator.taxableBase(inv);
		BigDecimal expected = TaxCalculator.tax(base, rate);
		BigDecimal diff = inv.taxCharged().subtract(expected);
		if (diff.signum() == 0)
			return new Finding(inv, period.name(), rate, expected, diff, Cause.CORRECT, "Correct.", List.of());

		// Each hypothesis: a mistake, and the charge it would produce. Every one that reproduces the charge is kept.
		List<Cause> fits = new ArrayList<>();
		Map<Cause, String> why = new LinkedHashMap<>();
		if (matches(TaxCalculator.perLine(inv.lines(), rate), inv.taxCharged())) {
			fits.add(Cause.ROUNDING);
			why.put(Cause.ROUNDING, "The vendor rounded tax on each line instead of on the total; immaterial.");
		}
		if (inv.taxCharged().signum() == 0) {
			fits.add(Cause.NO_TAX);
			why.put(Cause.NO_TAX, "No tax was charged on taxable items, so use tax of " + expected
					+ " is owed to the state.");
		}
		if (base.compareTo(inv.subtotal()) != 0 && matches(TaxCalculator.tax(inv.subtotal(), rate), inv.taxCharged())) {
			fits.add(Cause.TAX_ON_EXEMPT);
			why.put(Cause.TAX_ON_EXEMPT, "Tax was charged on exempt lines (" + exemptCategories(inv)
					+ "); the vendor should have honored the exemption.");
		}
		List<RatePeriod> history = rates.history(inv.shipTo());
		for (int i = 1; i < history.size(); i++) {
			RatePeriod before = history.get(i - 1), now = history.get(i);
			long days = ChronoUnit.DAYS.between(now.from(), inv.date());
			if (now.covers(inv.date()) && days < STALE_WINDOW_DAYS && before.combined().compareTo(rate) != 0
					&& matches(TaxCalculator.tax(base, before.combined()), inv.taxCharged())) {
				fits.add(Cause.STALE_RATE);
				why.put(Cause.STALE_RATE, "The vendor used " + pct(before.combined()) + ", the rate before " + now.from()
						+ ", " + days + " days after it changed to " + pct(rate) + ".");
			}
		}
		Optional<RatePeriod> origin = inv.vendorLocation() == null ? Optional.empty() : rates.rateOn(inv.vendorLocation(), inv.date());
		if (origin.isPresent() && origin.get().combined().compareTo(rate) != 0
				&& matches(TaxCalculator.tax(base, origin.get().combined()), inv.taxCharged())) {
			fits.add(Cause.ORIGIN_RATE);
			why.put(Cause.ORIGIN_RATE, "The vendor charged its own location's rate (" + origin.get().name() + ", "
					+ pct(origin.get().combined()) + "). Washington sales tax follows the delivery location, " + period.name()
					+ " at " + pct(rate) + ".");
		}
		if (fits.isEmpty()) {
			List<String> others = allOn.apply(inv.date()).stream()
					.filter(o -> o.combined().compareTo(rate) != 0 && matches(TaxCalculator.tax(base, o.combined()), inv.taxCharged()))
					.map(RatePeriod::name).toList();
			if (!others.isEmpty()) {
				fits.add(Cause.OTHER_JURISDICTION);
				why.put(Cause.OTHER_JURISDICTION, "The charge matches the rate of " + others.size() + " other location"
						+ (others.size() == 1 ? "" : "s") + " (e.g. " + String.join(", ", others.subList(0, Math.min(3, others.size())))
						+ ") instead of " + period.name() + "; the vendor probably has the wrong delivery address.");
			}
		}
		if (fits.isEmpty())
			return new Finding(inv, period.name(), rate, expected, diff, Cause.UNEXPLAINED,
					"No single known mistake reproduces this charge; review the invoice by hand.", List.of());
		Cause primary = fits.getFirst();
		return new Finding(inv, period.name(), rate, expected, diff, primary, why.get(primary), fits.subList(1, fits.size()));
	}

	/** Within a cent: vendors' systems round slightly differently, and a cent never decides a cause. */
	private static boolean matches(BigDecimal a, BigDecimal b) {
		return a.subtract(b).abs().compareTo(CENT) <= 0;
	}

	private static String pct(BigDecimal rate) {
		return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
	}

	private static String exemptCategories(Invoice inv) {
		return String.join(", ", inv.lines().stream().filter(l -> !l.category().taxable).map(l -> l.category().description).distinct().toList());
	}
}

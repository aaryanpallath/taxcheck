package com.aaryanpallath.taxcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.aaryanpallath.taxcheck.rates.RateRepository;
import com.aaryanpallath.taxcheck.review.Cause;
import com.aaryanpallath.taxcheck.review.Finding;
import com.aaryanpallath.taxcheck.review.Invoice;
import com.aaryanpallath.taxcheck.review.InvoiceReviewer;
import com.aaryanpallath.taxcheck.review.SampleInvoices;
import com.aaryanpallath.taxcheck.tax.Category;

/** Against the real Washington rate files bundled with the app. */
@SpringBootTest
class ReviewTest {
	@Autowired
	RateRepository rates;
	@Autowired
	InvoiceReviewer reviewer;

	static final LocalDate JAN_20_2026 = LocalDate.of(2026, 1, 20); // Seattle went from 10.35% to 10.55% on Jan 1, 2026

	private Finding review(String vendorLoc, String shipTo, LocalDate date, String charged, Invoice.Line... lines) {
		return reviewer.review(new Invoice("T", "Vendor", vendorLoc, shipTo, date, List.of(lines), new BigDecimal(charged)));
	}

	private static Invoice.Line line(Category c, String amount) {
		return new Invoice.Line(c, new BigDecimal(amount));
	}

	@Test
	void ratesChangeOnTheFirstDayOfTheQuarterAndBothEndsAreInclusive() {
		assertThat(rates.rateOn("1726", LocalDate.of(2025, 12, 31)).orElseThrow().combined()).isEqualByComparingTo("0.1035");
		assertThat(rates.rateOn("1726", LocalDate.of(2026, 1, 1)).orElseThrow().combined()).isEqualByComparingTo("0.1055");
		assertThat(rates.rateOn("1726", LocalDate.of(2027, 1, 1))).isEmpty(); // beyond the data
	}

	@Test
	void theLeadingZeroFixKeepsHistoriesWhole() {
		// Adams County ("0100") was "100" in two of the twelve files; its 8% rate never changed
		assertThat(rates.history("0100")).singleElement().satisfies(p -> {
			assertThat(p.from()).isEqualTo(LocalDate.of(2024, 1, 1));
			assertThat(p.to()).isEqualTo(LocalDate.of(2026, 12, 31));
		});
		assertThat(rates.rateOn("0100", LocalDate.of(2025, 2, 15))).isPresent();
	}

	@Test
	void diagnosesEachKindOfMistake() {
		var supplies = line(Category.OFFICE_SUPPLIES, "1000.00");
		assertThat(review("3210", "1726", JAN_20_2026, "105.50", supplies).cause()).isEqualTo(Cause.CORRECT);
		Finding stale = review("1726", "1726", JAN_20_2026, "103.50", supplies);
		assertThat(stale.cause()).isEqualTo(Cause.STALE_RATE);
		assertThat(stale.difference()).isEqualByComparingTo("-2.00");
		assertThat(stale.explanation()).contains("10.35%", "19 days after", "10.55%");
		// Spokane City's 9.1%: the vendor's own rate, but Washington taxes at the delivery location
		assertThat(review("3210", "1726", JAN_20_2026, "91.00", supplies).cause()).isEqualTo(Cause.ORIGIN_RATE);
		assertThat(review("3210", "1726", JAN_20_2026, "0.00", supplies).cause()).isEqualTo(Cause.NO_TAX);
		Finding exempt = review("3210", "1726", JAN_20_2026, "633.00", supplies, line(Category.MANUFACTURING_EQUIPMENT, "5000.00"));
		assertThat(exempt.cause()).isEqualTo(Cause.TAX_ON_EXEMPT);
		assertThat(exempt.overcharged()).isTrue();
		assertThat(review("3210", "9999", JAN_20_2026, "1.00", supplies).cause()).isEqualTo(Cause.UNKNOWN_LOCATION);
	}

	@Test
	void perLineRoundingIsReportedButNotMaterial() {
		// three $0.05 lines at 10.55%: each line's 0.0053 rounds up to 0.01 (0.03 in all); the total's 0.0158 rounds to 0.02
		var l = line(Category.OFFICE_SUPPLIES, "0.05");
		Finding f = review("1726", "1726", JAN_20_2026, "0.03", l, l, l);
		assertThat(f.cause()).isEqualTo(Cause.ROUNDING);
		assertThat(f.material()).isFalse();
	}

	@Test
	void namesAnotherJurisdictionWhenNoSimplerMistakeFits() {
		// 8% matches Adams County and others, not Seattle's rate history or the vendor's own location
		Finding f = review("1726", "1726", JAN_20_2026, "80.00", line(Category.OFFICE_SUPPLIES, "1000.00"));
		assertThat(f.cause()).isEqualTo(Cause.OTHER_JURISDICTION);
		assertThat(f.explanation()).contains("other location");
	}

	@Test
	void twoMistakesAtOnceAreLeftForAPerson() {
		// stale 10.35% on taxable and exempt lines together: no single mistake reproduces 621.00
		Finding f = review("1726", "1726", JAN_20_2026, "621.00", line(Category.OFFICE_SUPPLIES, "1000.00"),
				line(Category.MANUFACTURING_EQUIPMENT, "5000.00"));
		assertThat(f.cause()).isEqualTo(Cause.UNEXPLAINED);
	}

	@Test
	void reviewerRecoversPlantedMistakesOnSampleInvoices() {
		var sample = SampleInvoices.generate(rates, 600, 99);
		var findings = reviewer.review(sample.stream().map(SampleInvoices.Labeled::invoice).toList());
		long right = 0, materialTruth = 0, falseFlags = 0;
		for (int i = 0; i < sample.size(); i++) {
			Cause truth = sample.get(i).planted();
			boolean material = truth != Cause.CORRECT && truth != Cause.ROUNDING;
			if (material) {
				materialTruth++;
				if (findings.get(i).cause() == truth) right++;
			} else if (findings.get(i).material()) falseFlags++;
		}
		assertThat(falseFlags).isZero();
		assertThat((double) right / materialTruth).isGreaterThan(0.97); // misses: a wrong address whose rate equals the vendor's own
	}
}

package com.aaryanpallath.taxcheck.review;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.aaryanpallath.taxcheck.rates.RateRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * mvn spring-boot:run -Dspring-boot.run.arguments=--eval
 *
 * Reviews 3,000 synthetic invoices with planted mistakes and scores the diagnosis: does the reviewer flag every
 * invoice with a material error, and name the mistake that was planted? Writes results/review-eval.json.
 */
@Component
@Order(2)
public class EvalRunner implements ApplicationRunner {
	private final RateRepository rates;
	private final InvoiceReviewer reviewer;
	private final ApplicationContext ctx;

	public EvalRunner(RateRepository rates, InvoiceReviewer reviewer, ApplicationContext ctx) {
		this.rates = rates;
		this.reviewer = reviewer;
		this.ctx = ctx;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		if (!args.containsOption("eval")) return;
		List<SampleInvoices.Labeled> sample = SampleInvoices.generate(rates, 3000, 42);
		List<Finding> findings = reviewer.review(sample.stream().map(SampleInvoices.Labeled::invoice).toList());
		Map<String, Map<String, Integer>> confusion = new TreeMap<>();
		int materialPlanted = 0, flagged = 0, rightCause = 0, falseFlags = 0, ambiguous = 0;
		BigDecimal over = BigDecimal.ZERO, under = BigDecimal.ZERO;
		Map<Cause, Integer> planted = new EnumMap<>(Cause.class);
		for (int i = 0; i < sample.size(); i++) {
			Cause truth = sample.get(i).planted();
			Finding f = findings.get(i);
			planted.merge(truth, 1, Integer::sum);
			confusion.computeIfAbsent(truth.name(), k -> new TreeMap<>()).merge(f.cause().name(), 1, Integer::sum);
			boolean materialTruth = truth != Cause.CORRECT && truth != Cause.ROUNDING;
			if (materialTruth) {
				materialPlanted++;
				if (f.material()) flagged++;
				if (f.cause() == truth) rightCause++;
				if (!f.alsoFits().isEmpty()) ambiguous++;
			} else if (f.material()) falseFlags++;
			if (f.material()) {
				if (f.overcharged()) over = over.add(f.difference());
				else under = under.add(f.difference().negate());
			}
		}
		var changes = rates.changes();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("invoices", sample.size());
		out.put("planted", planted);
		out.put("invoices_with_material_errors", materialPlanted);
		out.put("flagged", flagged);
		out.put("root_cause_correct", rightCause);
		out.put("also_fit_another_cause", ambiguous);
		out.put("false_flags_on_correct_or_rounding", falseFlags);
		out.put("overcharged_refund_opportunity", over);
		out.put("undercharged_use_tax_owed", under);
		out.put("confusion_planted_to_diagnosed", confusion);
		out.put("rate_data", Map.of("locations", rates.history("0100").isEmpty() ? 0 : countLocations(),
				"rate_changes_2024_2026", changes.size(),
				"locations_that_changed", changes.stream().map(c -> c.code()).distinct().count()));
		Files.createDirectories(Path.of("results"));
		String json = JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(out);
		Files.writeString(Path.of("results/review-eval.json"), json + "\n");
		System.out.println(json);
		System.exit(SpringApplication.exit(ctx, () -> 0));
	}

	private int countLocations() {
		return rates.allOn(java.time.LocalDate.of(2026, 10, 1)).size();
	}
}

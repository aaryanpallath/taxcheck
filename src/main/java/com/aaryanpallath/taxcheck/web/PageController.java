package com.aaryanpallath.taxcheck.web;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.aaryanpallath.taxcheck.rates.RateLoader;
import com.aaryanpallath.taxcheck.rates.RateRepository;
import com.aaryanpallath.taxcheck.review.Cause;
import com.aaryanpallath.taxcheck.review.Finding;
import com.aaryanpallath.taxcheck.review.Invoice;
import com.aaryanpallath.taxcheck.review.InvoiceCsv;
import com.aaryanpallath.taxcheck.review.InvoiceReviewer;
import com.aaryanpallath.taxcheck.review.SampleInvoices;

/** Server-rendered pages (Spring MVC + Thymeleaf): the invoice review and the rate lookup. */
@Controller
public class PageController {
	private final RateRepository rates;
	private final InvoiceReviewer reviewer;

	public PageController(RateRepository rates, InvoiceReviewer reviewer) {
		this.rates = rates;
		this.reviewer = reviewer;
	}

	/** The review of 300 sample invoices, so the page shows something before anyone uploads. */
	@GetMapping("/")
	public String index(Model model) {
		List<Invoice> sample = SampleInvoices.generate(rates, 300, 7).stream().map(SampleInvoices.Labeled::invoice).toList();
		return show(model, reviewer.review(sample), "300 sample invoices");
	}

	@PostMapping("/review")
	public String upload(@RequestParam("file") MultipartFile file, Model model) throws IOException {
		try {
			List<Invoice> invoices = InvoiceCsv.read(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
			return show(model, reviewer.review(invoices), file.getOriginalFilename());
		} catch (InvoiceCsv.FormatException e) {
			model.addAttribute("error", e.getMessage());
			return index(model);
		}
	}

	private String show(Model model, List<Finding> findings, String source) {
		Map<String, Integer> byCause = new TreeMap<>();
		BigDecimal over = BigDecimal.ZERO, under = BigDecimal.ZERO;
		for (Finding f : findings) {
			byCause.merge(f.cause().label, 1, Integer::sum);
			if (f.material()) {
				if (f.overcharged()) over = over.add(f.difference());
				else under = under.add(f.difference().negate());
			}
		}
		model.addAttribute("source", source);
		model.addAttribute("count", findings.size());
		model.addAttribute("byCause", byCause);
		model.addAttribute("over", over);
		model.addAttribute("under", under);
		model.addAttribute("findings", findings.stream().filter(f -> f.cause() != Cause.CORRECT).toList());
		model.addAttribute("header", InvoiceCsv.HEADER);
		return "index";
	}

	@GetMapping("/rates")
	public String rates(@RequestParam(value = "q", required = false) String q, Model model) {
		model.addAttribute("q", q == null ? "" : q);
		model.addAttribute("results", q == null || q.isBlank() ? List.of() : rates.search(q.trim()));
		model.addAttribute("changes", rates.changes().stream().limit(25).toList());
		return "rates";
	}

	@GetMapping("/rates/{code}")
	public String history(@PathVariable String code, Model model) {
		String c;
		try {
			c = RateLoader.normalizeCode(code);
		} catch (IllegalArgumentException e) {
			model.addAttribute("error", e.getMessage());
			return rates(null, model);
		}
		model.addAttribute("code", c);
		model.addAttribute("history", rates.history(c));
		return "history";
	}
}

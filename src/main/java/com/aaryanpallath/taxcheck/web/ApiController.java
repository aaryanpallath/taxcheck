package com.aaryanpallath.taxcheck.web;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aaryanpallath.taxcheck.rates.RateLoader;
import com.aaryanpallath.taxcheck.rates.RateRepository;
import com.aaryanpallath.taxcheck.review.Finding;
import com.aaryanpallath.taxcheck.review.InvoiceCsv;
import com.aaryanpallath.taxcheck.review.InvoiceReviewer;

/**
 * GET  /api/rates/{code}?date=2026-04-15   the rate in force at a location on a date
 * POST /api/review  (text/csv body)        one result per invoice: expected tax, difference, cause, explanation
 */
@RestController
@RequestMapping("/api")
public class ApiController {
	private final RateRepository rates;
	private final InvoiceReviewer reviewer;

	public ApiController(RateRepository rates, InvoiceReviewer reviewer) {
		this.rates = rates;
		this.reviewer = reviewer;
	}

	@GetMapping("/rates/{code}")
	public ResponseEntity<?> rate(@PathVariable String code,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return rates.rateOn(RateLoader.normalizeCode(code), date).<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no rate for " + code + " on " + date)));
	}

	public record Result(String invoice, String shipTo, String shipToName, BigDecimal rate, BigDecimal charged,
			BigDecimal expected, BigDecimal difference, String cause, String explanation, List<String> alsoFits) {
		static Result of(Finding f) {
			return new Result(f.invoice().id(), f.invoice().shipTo(), f.shipToName(), f.rate(), f.invoice().taxCharged(),
					f.expected(), f.difference(), f.cause().name(), f.explanation(), f.alsoFits().stream().map(Enum::name).toList());
		}
	}

	@PostMapping(value = "/review", consumes = "text/csv")
	public List<Result> review(@RequestBody String csv) throws IOException, InvoiceCsv.FormatException {
		return reviewer.review(InvoiceCsv.read(new StringReader(csv))).stream().map(Result::of).toList();
	}

	@ExceptionHandler({InvoiceCsv.FormatException.class, IllegalArgumentException.class})
	public ResponseEntity<Map<String, String>> badRequest(Exception e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

}

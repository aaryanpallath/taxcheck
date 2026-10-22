package com.aaryanpallath.taxcheck.review;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aaryanpallath.taxcheck.rates.RateLoader;
import com.aaryanpallath.taxcheck.tax.Category;

/**
 * Invoices as CSV, one row per line item; the invoice-level fields repeat on every row:
 *
 * invoice_id,vendor,vendor_location,ship_to,invoice_date,category,amount,tax_charged
 * INV-1001,Acme Supply,1726,3210,2026-04-15,OFFICE_SUPPLIES,120.00,10.68
 */
public final class InvoiceCsv {
	public static final String HEADER = "invoice_id,vendor,vendor_location,ship_to,invoice_date,category,amount,tax_charged";
	public static final int MAX_ROWS = 50_000;

	private InvoiceCsv() {
	}

	public static class FormatException extends Exception {
		public FormatException(String message) {
			super(message);
		}
	}

	public static List<Invoice> read(Reader in) throws IOException, FormatException {
		BufferedReader r = new BufferedReader(in);
		String header = r.readLine();
		if (header == null || !header.replace("﻿", "").trim().equalsIgnoreCase(HEADER))
			throw new FormatException("the first line must be: " + HEADER);
		Map<String, Invoice> byId = new LinkedHashMap<>();
		String line;
		int n = 1;
		while ((line = r.readLine()) != null) {
			n++;
			if (line.isBlank()) continue;
			if (n > MAX_ROWS) throw new FormatException("more than " + MAX_ROWS + " rows");
			String[] c = line.split(",", -1);
			if (c.length != 8) throw new FormatException("line " + n + ": expected 8 columns, found " + c.length);
			try {
				String id = c[0].trim();
				if (id.isEmpty()) throw new FormatException("line " + n + ": missing invoice_id");
				LocalDate date = LocalDate.parse(c[4].trim());
				Category cat = Category.valueOf(c[5].trim().toUpperCase());
				BigDecimal amount = new BigDecimal(c[6].trim());
				BigDecimal tax = new BigDecimal(c[7].trim());
				if (amount.signum() < 0 || tax.signum() < 0) throw new FormatException("line " + n + ": negative amount");
				Invoice prev = byId.get(id);
				Invoice.Line l = new Invoice.Line(cat, amount);
				if (prev == null) {
					List<Invoice.Line> lines = new ArrayList<>();
					lines.add(l);
					String vendorLoc = blankToNull(c[2]);
					byId.put(id, new Invoice(id, c[1].trim(), vendorLoc == null ? null : RateLoader.normalizeCode(vendorLoc),
							RateLoader.normalizeCode(c[3]), date, lines, tax));
				} else {
					if (!prev.date().equals(date) || !prev.shipTo().equals(RateLoader.normalizeCode(c[3])) || prev.taxCharged().compareTo(tax) != 0)
						throw new FormatException("line " + n + ": invoice " + id + " has different header fields than its first line");
					prev.lines().add(l);
				}
			} catch (DateTimeParseException e) {
				throw new FormatException("line " + n + ": invoice_date must be YYYY-MM-DD");
			} catch (IllegalArgumentException e) { // bad category or number
				throw new FormatException("line " + n + ": " + (e instanceof NumberFormatException ? "amount and tax_charged must be numbers"
						: e.getMessage().startsWith("location code") ? e.getMessage()
						: "category must be one of " + java.util.Arrays.toString(Category.values())));
			}
		}
		return new ArrayList<>(byId.values());
	}

	public static String write(List<Invoice> invoices) {
		StringBuilder sb = new StringBuilder(HEADER).append('\n');
		for (Invoice inv : invoices)
			for (Invoice.Line l : inv.lines())
				sb.append(String.join(",", inv.id(), inv.vendor(), inv.vendorLocation() == null ? "" : inv.vendorLocation(),
						inv.shipTo(), inv.date().toString(), l.category().name(), l.amount().toPlainString(),
						inv.taxCharged().toPlainString())).append('\n');
		return sb.toString();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}

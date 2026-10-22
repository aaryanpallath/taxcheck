package com.aaryanpallath.taxcheck;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.aaryanpallath.taxcheck.review.InvoiceCsv;

import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
class WebTest {
	@Autowired
	MockMvc mvc;

	static final String CSV = InvoiceCsv.HEADER + "\n"
			+ "A1,Acme,3210,1726,2026-01-20,OFFICE_SUPPLIES,600.00,103.50\n"
			+ "A1,Acme,3210,1726,2026-01-20,COMPUTER_HARDWARE,400.00,103.50\n"
			+ "B2,Acme,,100,2025-02-15,OFFICE_SUPPLIES,100.00,8.00\n"; // a code that lost its leading zero, as in Excel

	@Test
	void apiReviewsCsvAndAcceptsShortCodes() throws Exception {
		mvc.perform(post("/api/review").contentType("text/csv").content(CSV)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].cause").value("STALE_RATE"))
				.andExpect(jsonPath("$[0].expected").value(105.50))
				.andExpect(jsonPath("$[1].shipTo").value("0100"))
				.andExpect(jsonPath("$[1].cause").value("CORRECT"));
	}

	@Test
	void apiRejectsBadInputWithTheLine() throws Exception {
		mvc.perform(post("/api/review").contentType("text/csv").content(InvoiceCsv.HEADER + "\nA1,Acme,,1726,20/01/2026,OFFICE_SUPPLIES,1,0\n"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("line 2: invoice_date must be YYYY-MM-DD"));
		mvc.perform(post("/api/review").contentType("text/csv").content(InvoiceCsv.HEADER + "\nA1,Acme,,1726,2026-01-20,FOOD,1,0\n"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("category must be one of")));
		mvc.perform(post("/api/review").contentType("text/csv").content("id,amount\n")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/review").contentType("text/csv").content(InvoiceCsv.HEADER
				+ "\nA1,Acme,,1726,2026-01-20,OFFICE_SUPPLIES,1,0\nA1,Acme,,1726,2026-01-21,OFFICE_SUPPLIES,1,0\n"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("different header fields")));
	}

	@Test
	void rateApi() throws Exception {
		mvc.perform(get("/api/rates/1726").param("date", "2026-01-01")).andExpect(status().isOk()).andExpect(jsonPath("$.combined").value(0.1055));
		mvc.perform(get("/api/rates/1726").param("date", "2030-01-01")).andExpect(status().isNotFound());
		mvc.perform(get("/api/rates/abc").param("date", "2026-01-01")).andExpect(status().isBadRequest());
	}

	@Test
	void pagesRender() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("300 sample invoices")));
		mvc.perform(get("/rates").param("q", "seattle")).andExpect(status().isOk()).andExpect(content().string(containsString("SEATTLE")));
		mvc.perform(get("/rates/100")).andExpect(status().isOk()).andExpect(content().string(containsString("ADAMS COUNTY")));
		mvc.perform(multipart("/review").file(new MockMultipartFile("file", "mine.csv", "text/csv", CSV.getBytes())))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Stale rate")));
		mvc.perform(multipart("/review").file(new MockMultipartFile("file", "bad.csv", "text/csv", "nope".getBytes())))
				.andExpect(status().isOk()).andExpect(content().string(containsString("the first line must be")));
	}
}

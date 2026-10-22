package com.aaryanpallath.taxcheck.review;

import java.math.BigDecimal;
import java.util.List;

/**
 * The review of one invoice. difference = charged - expected: positive means the vendor over-charged (a refund
 * claim against the vendor), negative means it under-charged (use tax the buyer owes the state).
 */
public record Finding(Invoice invoice, String shipToName, BigDecimal rate, BigDecimal expected, BigDecimal difference,
		Cause cause, String explanation, List<Cause> alsoFits) {

	public boolean material() {
		return cause != Cause.CORRECT && cause != Cause.ROUNDING;
	}

	public boolean overcharged() {
		return difference.signum() > 0;
	}
}

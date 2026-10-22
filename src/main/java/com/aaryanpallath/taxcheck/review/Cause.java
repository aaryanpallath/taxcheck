package com.aaryanpallath.taxcheck.review;

public enum Cause {
	CORRECT("Correct"),
	ROUNDING("Rounded per line"),
	STALE_RATE("Stale rate"),
	ORIGIN_RATE("Vendor's own location rate"),
	OTHER_JURISDICTION("Another jurisdiction's rate"),
	TAX_ON_EXEMPT("Tax on an exempt purchase"),
	NO_TAX("No tax charged"),
	UNKNOWN_LOCATION("Unknown location"),
	UNEXPLAINED("Unexplained");

	public final String label;

	Cause(String label) {
		this.label = label;
	}
}

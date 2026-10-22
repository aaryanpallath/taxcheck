package com.aaryanpallath.taxcheck.tax;

/**
 * What was bought, and whether the buyer owes sales tax on it. A simplified view of Washington taxability for a
 * manufacturer's purchases, for illustration; real determinations depend on facts and documentation.
 */
public enum Category {
	OFFICE_SUPPLIES(true, "office supplies"),
	COMPUTER_HARDWARE(true, "computer hardware"),
	PREWRITTEN_SOFTWARE(true, "prewritten (off-the-shelf) software"),
	RESALE_INVENTORY(false, "inventory bought for resale, with a reseller permit on file"),
	MANUFACTURING_EQUIPMENT(false, "machinery and equipment used directly in manufacturing (the M&E exemption)");

	public final boolean taxable;
	public final String description;

	Category(boolean taxable, String description) {
		this.taxable = taxable;
		this.description = description;
	}
}

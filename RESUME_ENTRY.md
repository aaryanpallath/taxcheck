# taxcheck — Sales & Use Tax Review for Purchase Invoices
Tech: Java, Spring Boot, Spring MVC, Thymeleaf, JDBC, SQL, H2, JUnit, Maven, GitHub Actions
- Built a Java/Spring MVC web app with a REST API that checks purchase invoices against three years of real Washington sales tax rates (416 rate changes across 266 locations) and explains every over- or under-charge.
- Diagnosed the root cause of each discrepancy (stale rate, vendor's local rate, wrong address, exempt items, rounding) by testing which mistake reproduces the charge; 679 of 687 planted errors named correctly, 0 false flags.
- Found the state's source files dropped leading zeros from 54 location codes in 2 quarters, hiding 21 rate changes; fixed it in the loader and the upload parser, with JUnit tests that fail without the fix.

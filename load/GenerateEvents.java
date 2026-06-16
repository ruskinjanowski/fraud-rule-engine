// Generates valid transaction events for the load test, one per line as
//   <customerId>\t<json>
// the tab-separated key/value form kafka-console-producer reads with parse.key=true.
// Keying by customerId keeps a customer's events on one partition, so the stateful rules
// still see them in order when ingestion is parallelised across partitions (docs/SCALING.md).
//
// Runs with the single-file source launcher — no build, just the JDK the project already
// requires (25):
//   java load/GenerateEvents.java > load/events.tsv
//   EVENTS=10000 CUSTOMERS=500 DAYS=3 java load/GenerateEvents.java > load/events.tsv
//
// Defaults: 50,000 events across 2,000 customers, occurredAt spread over the last 7 days.
// ~2,000 keys >> a handful of partitions (even spread) while each customer still accrues ~25
// events of real history — enough that the stateful rules and the anomaly baseline do real
// work rather than hitting an empty history. Rationale in docs/SCALING.md.
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class GenerateEvents {

	private static final String[] CATEGORIES =
			{"POS", "ATM", "ONLINE", "TRANSFER", "DEBIT_ORDER", "OTHER"};
	private static final String[] MERCHANTS =
			{"Checkers", "Woolworths", "Takealot", "Uber", "Shell", "Steers",
			 "Amazon", "Netflix", "PnP", "Makro", "Dis-Chem", "Mr D Food"};

	public static void main(String[] args) throws IOException {
		int events = envInt("EVENTS", 50_000);
		int customers = envInt("CUSTOMERS", 2_000);
		double days = envDouble("DAYS", 7);
		long seed = envInt("SEED", 42);

		// Mostly domestic (ZA); a sprinkling of foreign countries so the geographic and
		// impossible-travel rules occasionally have something to react to.
		String[] countries = buildCountries();

		Random rnd = new Random(seed);
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		long windowSeconds = (long) (days * 86_400);

		try (BufferedWriter out = new BufferedWriter(
				new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
			for (int i = 0; i < events; i++) {
				String customer = String.format(Locale.ROOT, "cust-%05d", rnd.nextInt(customers) + 1);
				// Long-tailed amount: most transactions are small, a few large enough to trip
				// the amount-threshold rule (limit 10,000) — a realistic-ish mix.
				double amount = Math.exp(5.0 + rnd.nextGaussian() * 1.1);
				Instant occurred = now.minusSeconds((long) (rnd.nextDouble() * windowSeconds));

				String json = "{"
						+ "\"eventId\":\"" + UUID.randomUUID() + "\","
						+ "\"transactionId\":\"txn-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "\","
						+ "\"customerId\":\"" + customer + "\","
						+ "\"amount\":" + String.format(Locale.ROOT, "%.2f", amount) + ","
						+ "\"currency\":\"ZAR\","
						+ "\"category\":\"" + CATEGORIES[rnd.nextInt(CATEGORIES.length)] + "\","
						+ "\"merchant\":\"" + MERCHANTS[rnd.nextInt(MERCHANTS.length)] + "\","
						+ "\"country\":\"" + countries[rnd.nextInt(countries.length)] + "\","
						+ "\"occurredAt\":\"" + occurred + "\""
						+ "}";

				out.write(customer);
				out.write('\t');
				out.write(json);
				out.write('\n');
			}
		}
	}

	private static String[] buildCountries() {
		String[] foreign = {"GB", "US", "NA", "BW", "AE", "MU", "DE", "NG", "IN", "KE"};
		String[] c = new String[90 + foreign.length];
		int i = 0;
		for (; i < 90; i++) {
			c[i] = "ZA";
		}
		for (String f : foreign) {
			c[i++] = f;
		}
		return c;
	}

	private static int envInt(String name, int dflt) {
		String v = System.getenv(name);
		return (v == null || v.isBlank()) ? dflt : Integer.parseInt(v.trim());
	}

	private static double envDouble(String name, double dflt) {
		String v = System.getenv(name);
		return (v == null || v.isBlank()) ? dflt : Double.parseDouble(v.trim());
	}
}

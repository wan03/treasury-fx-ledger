package com.wex.fx;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context boots against a prod-parity Postgres
 * with the least-privilege role split in place (Flyway as {@code migration}, the
 * app as {@code app}). See {@link AbstractPostgresIT}.
 */
class CurrencyLedgerApplicationTests extends AbstractPostgresIT {

	@Test
	void contextLoads() {
	}

}

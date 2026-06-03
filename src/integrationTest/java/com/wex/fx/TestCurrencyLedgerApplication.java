package com.wex.fx;

import org.springframework.boot.SpringApplication;

public class TestCurrencyLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(CurrencyLedgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

package com.walletledger;

import org.springframework.boot.SpringApplication;

public class TestWalletledgerApplication {

	public static void main(String[] args) {
		SpringApplication.from(WalletledgerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

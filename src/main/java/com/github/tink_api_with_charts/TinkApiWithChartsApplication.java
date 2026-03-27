package com.github.tink_api_with_charts;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class TinkApiWithChartsApplication {

	public static void main(String[] args) {
		SpringApplication.run(TinkApiWithChartsApplication.class, args);
	}

}

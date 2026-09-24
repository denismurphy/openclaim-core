package com.openclaim.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OpenClaimApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenClaimApplication.class, args);
    }
}

package com.skala.chagok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChagokApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChagokApplication.class, args);
    }

}

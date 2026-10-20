package dev.splitledger;

import org.springframework.boot.SpringApplication;

/** Runs the app locally against a throwaway PostgreSQL container: ./mvnw spring-boot:test-run */
public class TestSplitledgerApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(SplitledgerApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}

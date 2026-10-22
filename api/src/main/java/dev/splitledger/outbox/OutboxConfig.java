package dev.splitledger.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

/**
 * Publishing is switched on by {@code splitledger.outbox.queue-url}. Without it (local runs, most
 * tests) events are still written to the outbox table, they are just not sent anywhere.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("splitledger.outbox.queue-url")
@EnableScheduling
class OutboxConfig {

    /**
     * On ECS the default provider chain finds the task role's credentials. An endpoint override is
     * only used for a local SQS emulator in tests, which accepts any credentials.
     */
    @Bean(destroyMethod = "close")
    SqsClient sqsClient(@Value("${splitledger.outbox.region:eu-central-1}") String region,
                        @Value("${splitledger.outbox.endpoint:}") String endpoint) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (endpoint.isBlank()) {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        } else {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}

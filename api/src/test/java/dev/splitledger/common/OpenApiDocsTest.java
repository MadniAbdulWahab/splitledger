package dev.splitledger.common;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocsTest extends IntegrationTest {

    @Test
    void describesEveryEndpointAndTheTokenScheme() {
        MvcTestResult docs = anonymous().get().uri("/v3/api-docs").exchange();

        assertThat(docs).hasStatusOk();
        assertThat(docs).bodyJson().extractingPath("$.info.title").isEqualTo("SplitLedger API");
        assertThat(docs).bodyJson().extractingPath("$.components.securitySchemes.apiToken.scheme").isEqualTo("bearer");
        assertThat(docs).bodyJson().extractingPath("$.paths").asMap().containsKeys(
                "/users", "/me", "/groups", "/invitations/accept",
                "/groups/{groupId}/expenses", "/groups/{groupId}/expenses/search",
                "/groups/{groupId}/expenses/{expenseId}/reversal", "/groups/{groupId}/expenses/{expenseId}/correction",
                "/groups/{groupId}/payments/{paymentId}/reversal", "/groups/{groupId}/imports",
                "/groups/{groupId}/bank-transactions/{transactionId}/confirmation",
                "/groups/{groupId}/balances", "/groups/{groupId}/settlement", "/groups/{groupId}/payments",
                "/groups/{groupId}/verify");
        assertThat(anonymous().get().uri("/swagger-ui/index.html")).hasStatusOk();
    }
}

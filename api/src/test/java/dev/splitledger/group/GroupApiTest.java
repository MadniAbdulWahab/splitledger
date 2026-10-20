package dev.splitledger.group;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

class GroupApiTest extends IntegrationTest {

    @Test
    void createsGroupAndStoresIbanNormalized() {
        long groupId = createGroup("WG Poppelsdorf");

        MvcTestResult result = mvc.post().uri("/groups/{id}/members", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName": "Mia", "iban": "de89 3704 0044 0532 0130 00", "bic": "COBADEFFXXX"}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.iban").isEqualTo("DE89370400440532013000");
        assertThat(mvc.get().uri("/groups/{id}/members", groupId))
                .bodyJson().extractingPath("$[*].displayName").asArray().containsExactly("Mia");
    }

    @Test
    void rejectsInvalidIbanAsProblemDetails() {
        long groupId = createGroup("Invalid IBAN");

        MvcTestResult result = mvc.post().uri("/groups/{id}/members", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName": "Jonas", "iban": "DE88370400440532013000"}
                        """)
                .exchange();

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("iban");
    }

    @Test
    void rejectsDuplicateMemberName() {
        long groupId = createGroup("Duplicates");
        addMember(groupId, "Lena");

        MvcTestResult result = mvc.post().uri("/groups/{id}/members", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Lena\"}")
                .exchange();

        assertThat(result).hasStatus(409);
    }

    @Test
    void unknownGroupIsNotFound() {
        assertThat(mvc.get().uri("/groups/{id}", Long.MAX_VALUE))
                .hasStatus(404)
                .bodyJson().extractingPath("$.detail").asString().contains("does not exist");
    }
}

package dev.splitledger;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.security.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.Filter;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Base class for tests against the full app and a real PostgreSQL. Before each test a new user
 * "Anna" is registered, and {@link #mvc} sends her token with every request. Tests never clean up:
 * each one creates its own users and groups, so they cannot see each other's data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected UserService userService;

    /** Runs first, as in the application (request id, request log line). */
    @Autowired
    @Qualifier("requestLoggingFilter")
    private Filter requestLoggingFilter;

    /** Requests as the current test user. */
    protected MockMvcTester mvc;

    protected UserService.Registration me;

    @BeforeEach
    void signIn() {
        me = userService.register("Anna");
        mvc = as(me.token());
    }

    /** Requests with another user's token, through the logging filter and the security filter chain. */
    protected MockMvcTester as(String token) {
        return MockMvcTester.from(context, builder -> builder
                .addFilters(requestLoggingFilter)
                .apply(springSecurity())
                .defaultRequest(get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .build());
    }

    protected MockMvcTester anonymous() {
        return MockMvcTester.from(context, builder -> builder
                .addFilters(requestLoggingFilter)
                .apply(springSecurity())
                .build());
    }

    /** Creates a group as the current user, who becomes its first member ("Anna"). */
    protected long createGroup(String name) {
        MvcTestResult result = mvc.post().uri("/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\"}".formatted(name))
                .exchange();
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    protected long myMemberId(long groupId) {
        return jdbc.queryForObject("select id from member where group_id = ? and user_id = ?", Long.class, groupId, me.id());
    }

    /** Adds a member without an account yet and returns the member id. */
    protected long addMember(long groupId, String displayName) {
        return longAt(addMemberWithInvite(groupId, displayName), "$.member.id");
    }

    protected MvcTestResult addMemberWithInvite(long groupId, String displayName) {
        MvcTestResult result = mvc.post().uri("/groups/{id}/members", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"%s\"}".formatted(displayName))
                .exchange();
        assertThat(result).hasStatus(201);
        return result;
    }

    /** Creates an expense split equally between the participants and returns its id. */
    protected long createExpense(long groupId, long payerId, long amountCents, String description, long... participants) {
        StringBuilder list = new StringBuilder();
        for (long participant : participants) {
            list.append(list.isEmpty() ? "" : ", ").append("{\"memberId\": ").append(participant).append('}');
        }
        MvcTestResult result = mvc.post().uri("/groups/{id}/expenses", groupId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerId": %d, "amountCents": %d, "description": "%s", "expenseDate": "2026-10-21",
                         "splitMode": "EQUAL", "participants": [%s]}
                        """.formatted(payerId, amountCents, description, list))
                .exchange();
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    protected static long idOf(MvcTestResult result) {
        return longAt(result, "$.id");
    }

    protected static long longAt(MvcTestResult result, String path) {
        Number value = JsonPath.read(body(result), path);
        return value.longValue();
    }

    protected static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

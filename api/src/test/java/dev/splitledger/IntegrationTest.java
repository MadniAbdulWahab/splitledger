package dev.splitledger;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for tests against the full app and a real PostgreSQL. Tests never clean up: each one
 * creates its own group, so they cannot see each other's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected long createGroup(String name) {
        MvcTestResult result = mvc.post().uri("/groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\"}".formatted(name))
                .exchange();
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    protected long addMember(long groupId, String displayName) {
        MvcTestResult result = mvc.post().uri("/groups/{id}/members", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"%s\"}".formatted(displayName))
                .exchange();
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    protected static long idOf(MvcTestResult result) {
        return longAt(result, "$.id");
    }

    protected static long longAt(MvcTestResult result, String path) {
        try {
            Number value = JsonPath.read(result.getResponse().getContentAsString(), path);
            return value.longValue();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

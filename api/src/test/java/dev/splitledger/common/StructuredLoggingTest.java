package dev.splitledger.common;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs with the JSON log format used on AWS and checks the fields CloudWatch relies on. */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.structured.format.console=ecs")
class StructuredLoggingTest extends IntegrationTest {

    @Test
    void everyRequestEndsWithOneJsonLineCarryingRequestAndGroupIds(CapturedOutput output) {
        long groupId = createGroup("Logged");

        MvcTestResult result = mvc.get().uri("/groups/{id}/balances", groupId)
                .header(RequestLoggingFilter.REQUEST_ID_HEADER, "demo-123")
                .exchange();

        assertThat(result).hasStatusOk().hasHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "demo-123");
        Map<String, Object> line = lastJsonLineContaining(output, "\"demo-123\"");
        assertThat(line).containsEntry("requestId", "demo-123")
                .containsEntry("groupId", String.valueOf(groupId))
                .containsEntry("method", "GET")
                .containsEntry("path", "/groups/" + groupId + "/balances")
                .containsEntry("status", 200)
                .containsKey("durationMs");
    }

    @Test
    void rejectedRequestsAreLoggedTooAndUnsafeIdsAreReplaced(CapturedOutput output) {
        MvcTestResult result = anonymous().get().uri("/groups/1/balances")
                .header(RequestLoggingFilter.REQUEST_ID_HEADER, "evil\nfake log line")
                .exchange();

        assertThat(result).hasStatus(401);
        String newId = result.getResponse().getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
        assertThat(newId).matches("[0-9a-f-]{36}");
        assertThat(lastJsonLineContaining(output, newId)).containsEntry("status", 401);
    }

    private static Map<String, Object> lastJsonLineContaining(CapturedOutput output, String text) {
        List<String> lines = Arrays.stream(output.getOut().split("\\R"))
                .filter(line -> line.startsWith("{") && line.contains(text) && line.contains("\"status\""))
                .toList();
        assertThat(lines).as("JSON request log line containing %s", text).isNotEmpty();
        return JsonPath.read(lines.getLast(), "$");
    }
}

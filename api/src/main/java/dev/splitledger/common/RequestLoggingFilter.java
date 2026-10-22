package dev.splitledger.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives every request an id and logs one line when it finishes.
 *
 * <ul>
 *   <li>{@code requestId} (from an incoming X-Request-Id header, otherwise new) and {@code groupId}
 *       (from /groups/{id}/...) go into the MDC, so every log line written while handling the
 *       request carries them. In CloudWatch, all lines of one request or one group can be found
 *       with a single filter. The id is also returned as X-Request-Id for bug reports.</li>
 *   <li>The summary line has the fields method, path, status and durationMs. With JSON logs, a
 *       CloudWatch metric filter like {@code { $.status >= 500 }} turns it into an error metric.</li>
 * </ul>
 * Runs before Spring Security, so rejected requests (401/403) are logged as well. Health checks
 * are not logged: ECS calls them every 30 seconds.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern GROUP_PATH = Pattern.compile("^/groups/(\\d{1,18})(?:/.*)?$");
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String requestId = requestIdOf(request);
        MDC.put("requestId", requestId);
        Matcher group = GROUP_PATH.matcher(request.getRequestURI());
        if (group.matches()) {
            MDC.put("groupId", group.group(1));
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            LoggingEventBuilder event = status >= 500 ? log.atError() : log.atInfo();
            event.addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", status)
                    .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("{} {} -> {}", request.getMethod(), request.getRequestURI(), status);
            MDC.remove("requestId");
            MDC.remove("groupId");
        }
    }

    /** Reuses the caller's id only if it is short and harmless, so nobody can inject text into logs. */
    private static String requestIdOf(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }
}

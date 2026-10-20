package dev.splitledger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer slt_..." and, if the token belongs to a user, marks the request as
 * authenticated. An unknown or missing token simply leaves the request anonymous; the security
 * rules then answer 401 for anything that needs a user.
 *
 * <p>Deliberately not a Spring bean: Spring Boot would also register every Filter bean in the
 * servlet container, and the filter would run twice.
 */
class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final UserService userService;

    ApiTokenAuthenticationFilter(UserService userService) {
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            userService.authenticate(header.substring(BEARER.length()).strip()).ifPresent(user ->
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())));
        }
        chain.doFilter(request, response);
    }
}

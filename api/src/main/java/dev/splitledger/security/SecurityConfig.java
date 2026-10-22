package dev.splitledger.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, UserService userService,
                            AuthorizationManager<RequestAuthorizationContext> groupMembership) throws Exception {
        http
                // Clients send the token in a header on every request; browsers never attach it
                // automatically, so cross-site request forgery is not possible and CSRF tokens are off.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                // The web page may only load scripts, styles and data from this server; injected
                // markup could not run code or send the token elsewhere. blob: is the GiroCode image,
                // data: covers Swagger UI's icons.
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; "
                                + "base-uri 'self'; form-action 'self'")))
                .addFilterBefore(new ApiTokenAuthenticationFilter(userService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        // The web page itself is public; everything it does goes through the API with a token.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.svg", "/favicon.ico").permitAll()
                        .requestMatchers("/groups/{groupId}", "/groups/{groupId}/**").access(groupMembership)
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> problem(response, 401, "Unauthorized",
                                "Send a valid API token: Authorization: Bearer slt_..."))
                        .accessDeniedHandler((request, response, e) -> problem(response, 403, "Forbidden",
                                "You are not a member of this group")));
        return http.build();
    }

    /** Same RFC 9457 shape as the other errors; these two are raised before Spring MVC is involved. */
    private static void problem(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}""".formatted(title, status, detail));
    }
}

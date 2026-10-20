package dev.splitledger.security;

/** The authenticated caller, available in controllers via {@code @AuthenticationPrincipal}. */
public record CurrentUser(long id, String displayName) {
}

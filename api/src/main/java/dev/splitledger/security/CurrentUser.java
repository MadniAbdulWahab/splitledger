package dev.splitledger.security;

/**
 * The authenticated caller, available in controllers via {@code @AuthenticationPrincipal}.
 * {@code sessionId} is the signed-in device, or null when the caller used an API token.
 */
public record CurrentUser(long id, String displayName, Long sessionId) {

    public CurrentUser(long id, String displayName) {
        this(id, displayName, null);
    }
}

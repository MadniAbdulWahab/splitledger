package dev.splitledger.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Lower case; null for accounts that only have an API token. */
    @Column(length = 30)
    private String username;

    /** bcrypt; null until the user sets a password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    protected AppUser() {
    }

    AppUser(String displayName, String tokenHash) {
        this.displayName = displayName;
        this.tokenHash = tokenHash;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    void changeUsername(String username) {
        this.username = username;
    }

    void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUsername() {
        return username;
    }

    String getPasswordHash() {
        return passwordHash;
    }
}

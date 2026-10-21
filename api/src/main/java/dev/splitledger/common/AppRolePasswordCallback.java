package dev.splitledger.common;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * After every migration, gives the application role {@value #APP_ROLE} a login with the
 * application's configured database password. Runs as the migration (owner) user.
 *
 * <p>Only active when the application connects as {@value #APP_ROLE} and Flyway as someone else
 * (the AWS setup). Locally both are the same user and nothing happens. Setting the password on
 * every start keeps it in sync when the password in SSM is changed.
 */
@Component
class AppRolePasswordCallback implements Callback {

    static final String APP_ROLE = "splitledger_app";
    private static final Logger log = LoggerFactory.getLogger(AppRolePasswordCallback.class);

    private final String appUser;
    private final String appPassword;
    private final String migrationUser;

    AppRolePasswordCallback(@Value("${spring.datasource.username:}") String appUser,
                            @Value("${spring.datasource.password:}") String appPassword,
                            @Value("${spring.flyway.user:}") String migrationUser) {
        this.appUser = appUser;
        this.appPassword = appPassword;
        this.migrationUser = migrationUser;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE
                && APP_ROLE.equals(appUser)
                && !migrationUser.isEmpty()
                && !migrationUser.equals(appUser);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (appPassword.isEmpty()) {
            throw new IllegalStateException("spring.datasource.password is empty; the application role needs a password");
        }
        try {
            setPassword(context.getConnection(), APP_ROLE, appPassword);
            log.info("Login password of database role {} set", APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not set the password of role " + APP_ROLE, e);
        }
    }

    /**
     * ALTER ROLE cannot take bind parameters, so PostgreSQL's format() quotes the name (%I) and the
     * password (%L) itself; the password is passed as a parameter and never concatenated in Java.
     */
    static void setPassword(Connection connection, String role, String password) throws SQLException {
        String sql;
        try (PreparedStatement quote = connection.prepareStatement("select format('alter role %I with login password %L', ?, ?)")) {
            quote.setString(1, role);
            quote.setString(2, password);
            try (ResultSet rs = quote.executeQuery()) {
                rs.next();
                sql = rs.getString(1);
            }
        }
        try (Statement alter = connection.createStatement()) {
            alter.execute(sql);
        }
    }

    @Override
    public String getCallbackName() {
        return "app-role-password";
    }
}

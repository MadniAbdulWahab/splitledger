package dev.splitledger.common;

import com.zaxxer.hikari.HikariDataSource;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application role may read and insert everywhere and update only the mutable tables. Every
 * statement runs inside a transaction with SET LOCAL ROLE, so PostgreSQL checks it exactly as it
 * would for the application on AWS.
 */
class AppRolePrivilegesTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private long groupId;

    @BeforeEach
    void createData() {
        groupId = createGroup("Privileges");
        long anna = myMemberId(groupId);
        createExpense(groupId, anna, 1000, "Something", anna);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select count(*) from ledger_entry",
            "select count(*) from expense",
            "update member set display_name = display_name where group_id = %d",
            "select id from expense_group where id = %d for no key update",
            "select id from bank_transaction where group_id = %d for update",
            "update outbox_event set published_at = now() where group_id = %d",
    })
    void appRoleCanDoWhatTheApplicationNeeds(String sql) {
        asAppRole(sql.formatted(groupId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update ledger_entry set amount_cents = 1 where group_id = %d",
            "delete from ledger_entry where group_id = %d",
            "update expense set amount_cents = 1 where group_id = %d",
            "delete from member where group_id = %d",
            "update expense_group set created_at = now() where id = %d",
            "select count(*) from flyway_schema_history where %d > 0",
            "alter table ledger_entry disable trigger ledger_entry_append_only -- %d",
            "drop table payment -- %d",
            "update outbox_event set payload = '{}' where group_id = %d",
            "delete from outbox_event where group_id = %d",
    })
    void appRoleCannotChangeHistoryOrSchema(String sql) {
        // Spring reports SQLSTATE class 42 as "bad SQL grammar"; PostgreSQL's own message is the root cause.
        assertThatThrownBy(() -> asAppRole(sql.formatted(groupId)))
                .rootCause()
                .hasMessageMatching("(?s).*(permission denied|must be owner).*");
    }

    @Test
    void callbackGivesTheRoleALoginWithQuotedPassword() throws SQLException {
        String password = "it's a \"test\" password; drop table member; --";
        try (Connection owner = dataSource.getConnection()) {
            AppRolePasswordCallback.setPassword(owner, AppRolePasswordCallback.APP_ROLE, password);
        }

        String url = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
        try (Connection app = DriverManager.getConnection(url, AppRolePasswordCallback.APP_ROLE, password);
             Statement statement = app.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select current_user, (select count(*) from member)")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("splitledger_app");
                assertThat(rs.getLong(2)).isPositive();
            }
            assertThatThrownBy(() -> statement.executeUpdate("delete from ledger_entry"))
                    .hasMessageContaining("permission denied");
        }
    }

    private void asAppRole(String sql) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local role splitledger_app");
            jdbc.execute(sql);
        });
    }
}

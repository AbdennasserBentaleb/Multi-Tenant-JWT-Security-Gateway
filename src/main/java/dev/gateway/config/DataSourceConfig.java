package dev.gateway.config;

import dev.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Configures a {@link TenantAwareDataSource} that wraps HikariCP.
 *
 * <h2>How RLS is wired to the connection pool</h2>
 * Every time a JDBC connection is borrowed from HikariCP, the wrapper executes:
 * 
 * <pre>{@code
 *   SET LOCAL app.current_tenant = '<uuid>';
 * }</pre>
 * 
 * inside the same JDBC statement. {@code SET LOCAL} is transaction-scoped in
 * PostgreSQL — it is automatically cleared when the transaction commits or
 * rolls back, making it safe to use with connection pooling.
 *
 * <p>
 * The RLS policy ({@code V2__enable_rls.sql}) reads this session variable:
 * 
 * <pre>{@code
 *   USING (tenant_id = current_setting('app.current_tenant', true)::uuid)
 * }</pre>
 *
 * <h2>Why not Hibernate multi-tenancy?</h2>
 * Hibernate's built-in multi-tenancy support requires a separate schema or
 * datasource per tenant — that is operationally expensive at scale. Our
 * approach
 * uses a single schema with PostgreSQL RLS, which is both cheaper and more
 * compliant with strict data-protection requirements (all data stays in one
 * audit-able place).
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikariDataSource) {
        return new TenantAwareDataSource(hikariDataSource);
    }

    // ── Inner class ──────────────────────────────────────────────────────────

    /**
     * A thin {@link DataSource} decorator that injects the current tenant ID
     * into every JDBC connection before the application code runs a query.
     */
    static final class TenantAwareDataSource
            extends org.springframework.jdbc.datasource.DelegatingDataSource {

        private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

        TenantAwareDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            applyTenantToConnection(connection);
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection connection = super.getConnection(username, password);
            applyTenantToConnection(connection);
            return connection;
        }

        private void applyTenantToConnection(Connection connection) throws SQLException {
            if (!TenantContext.isBound()) {
                log.trace("No tenant in scope; clearing app.current_tenant for connection {}", connection);
                try (java.sql.Statement st = connection.createStatement()) {
                    st.execute("SET LOCAL app.current_tenant = ''");
                } catch (SQLException e) {
                    // Ignore if variable not yet defined
                }
                return;
            }

            UUID tenantId = TenantContext.getCurrentTenant();
            log.debug("Applying tenant [{}] to JDBC connection", tenantId);

            String sql = "SET LOCAL app.current_tenant = '" + tenantId.toString() + "'";
            try (java.sql.Statement st = connection.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                log.error("Failed to set app.current_tenant on connection; closing to prevent data leak", e);
                // Close the connection to prevent it being used without RLS context
                connection.close();
                throw e;
            }
        }
    }
}

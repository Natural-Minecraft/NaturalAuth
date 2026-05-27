package id.naturalsmp.naturalauth.velocity.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.*;
import java.util.UUID;

public class DatabaseManager {

    private final Logger logger;
    private HikariDataSource dataSource;
    private String usersTable;
    private String sessionsTable;

    public DatabaseManager(Logger logger) {
        this.logger = logger;
    }

    public void init(String host, int port, String dbName, String username, String password, String prefix) {
        usersTable = prefix + "users";
        sessionsTable = prefix + "sessions";

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true");
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(5000);

        dataSource = new HikariDataSource(config);

        createTables();
        runMigrations();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // Users table — includes rules_accepted and premium columns
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + usersTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(16) NOT NULL UNIQUE, " +
                    "password_hash VARCHAR(60) NOT NULL, " +
                    "rules_accepted TINYINT NOT NULL DEFAULT 0, " +
                    "premium TINYINT NOT NULL DEFAULT 0, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            // Sessions table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + sessionsTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "ip VARCHAR(45) NOT NULL, " +
                    "session_token VARCHAR(64) NOT NULL, " +
                    "expiry TIMESTAMP NOT NULL" +
                    ")");

            logger.info("Database tables initialized successfully.");
        } catch (SQLException e) {
            logger.error("Failed to create database tables", e);
        }
    }

    /**
     * Runs schema migrations for servers that already have older table structures.
     */
    private void runMigrations() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            
            // Check rules_accepted column; add it if missing
            try (ResultSet columns = meta.getColumns(null, null, usersTable, "rules_accepted")) {
                if (!columns.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + usersTable +
                                " ADD COLUMN rules_accepted TINYINT NOT NULL DEFAULT 0 AFTER password_hash");
                        logger.info("Migration: added rules_accepted column to " + usersTable);
                    }
                }
            }

            // Check premium column; add it if missing
            try (ResultSet columns = meta.getColumns(null, null, usersTable, "premium")) {
                if (!columns.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + usersTable +
                                " ADD COLUMN premium TINYINT NOT NULL DEFAULT 0 AFTER rules_accepted");
                        logger.info("Migration: added premium column to " + usersTable);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to run database migrations", e);
        }
    }

    // ───── User Methods ─────────────────────────────────────────────────────

    public boolean isRegistered(String username) {
        String query = "SELECT 1 FROM " + usersTable + " WHERE LOWER(username) = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, username.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Error checking registration status for: " + username, e);
            return false;
        }
    }

    public String getPasswordHash(String username) {
        String query = "SELECT password_hash FROM " + usersTable + " WHERE LOWER(username) = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, username.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("password_hash");
            }
        } catch (SQLException e) {
            logger.error("Error getting password hash for: " + username, e);
        }
        return null;
    }

    public boolean registerUser(UUID uuid, String username, String passwordHash) {
        String query = "INSERT INTO " + usersTable + " (uuid, username, password_hash) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to register user: " + username, e);
            return false;
        }
    }

    public boolean updatePassword(UUID uuid, String passwordHash) {
        String query = "UPDATE " + usersTable + " SET password_hash = ? WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, passwordHash);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update password for UUID: " + uuid, e);
            return false;
        }
    }

    // ───── Rules Methods ────────────────────────────────────────────────────

    /**
     * Returns true if the player with the given username has accepted the server rules.
     */
    public boolean hasAcceptedRules(String username) {
        String query = "SELECT rules_accepted FROM " + usersTable + " WHERE LOWER(username) = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, username.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("rules_accepted") == 1;
            }
        } catch (SQLException e) {
            logger.error("Error checking rules_accepted for: " + username, e);
        }
        return false;
    }

    /**
     * Marks the player as having accepted the server rules.
     */
    public void setRulesAccepted(UUID uuid) {
        String query = "UPDATE " + usersTable + " SET rules_accepted = 1 WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set rules_accepted for UUID: " + uuid, e);
        }
    }

    // ───── Session Methods ───────────────────────────────────────────────────

    public boolean saveSession(UUID uuid, String ip, String token, int expiryHours) {
        String query = "INSERT INTO " + sessionsTable + " (uuid, ip, session_token, expiry) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE ip = ?, session_token = ?, expiry = ?";
        Timestamp expiryTimestamp = new Timestamp(System.currentTimeMillis() + (expiryHours * 3600000L));
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ps.setString(3, token);
            ps.setTimestamp(4, expiryTimestamp);
            ps.setString(5, ip);
            ps.setString(6, token);
            ps.setTimestamp(7, expiryTimestamp);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to save session for UUID: " + uuid, e);
            return false;
        }
    }

    public boolean validateSession(UUID uuid, String ip, String token) {
        String query = "SELECT expiry FROM " + sessionsTable + " WHERE uuid = ? AND ip = ? AND session_token = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ps.setString(3, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getTimestamp("expiry").after(new Timestamp(System.currentTimeMillis()));
                }
            }
        } catch (SQLException e) {
            logger.error("Error validating session for UUID: " + uuid, e);
        }
        return false;
    }

    public void clearSession(UUID uuid) {
        String query = "DELETE FROM " + sessionsTable + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to clear session for UUID: " + uuid, e);
        }
    }

    public String getSessionToken(UUID uuid, String ip) {
        String query = "SELECT session_token, expiry FROM " + sessionsTable + " WHERE uuid = ? AND ip = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp expiry = rs.getTimestamp("expiry");
                    if (expiry.after(new Timestamp(System.currentTimeMillis()))) {
                        return rs.getString("session_token");
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting session token for UUID: " + uuid, e);
        }
        return null;
    }

    public boolean isPremium(String username) {
        String query = "SELECT premium FROM " + usersTable + " WHERE LOWER(username) = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, username.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("premium") == 1;
            }
        } catch (SQLException e) {
            logger.error("Error checking premium status for: " + username, e);
        }
        return false;
    }

    public void setPremium(UUID uuid, boolean premium) {
        String query = "UPDATE " + usersTable + " SET premium = ? WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setInt(1, premium ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set premium status for UUID: " + uuid, e);
        }
    }
}

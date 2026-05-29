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
    private String otpsTable;
    private String logsTable;

    public DatabaseManager(Logger logger) {
        this.logger = logger;
    }

    public void init(String host, int port, String dbName, String username, String password, String prefix) {
        // Anti-SQL Injection validation using Regex
        // Table names and prefixes cannot be parameterized, so they must be strictly alphanumeric/underscore
        if (prefix == null || !prefix.matches("^[a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Database prefix contains invalid characters! Only alphanumeric and underscores are allowed.");
        }
        usersTable = prefix + "users";
        sessionsTable = prefix + "sessions";
        otpsTable = prefix + "otps";
        logsTable = prefix + "logs";

        if (!usersTable.matches("^[a-zA-Z0-9_]+$") ||
            !sessionsTable.matches("^[a-zA-Z0-9_]+$") ||
            !otpsTable.matches("^[a-zA-Z0-9_]+$") ||
            !logsTable.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Database table names contain invalid characters! Only alphanumeric and underscores are allowed.");
        }

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
            // Users table — includes rules_accepted, premium, email, and phone_number columns
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + usersTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(16) NOT NULL UNIQUE, " +
                    "password_hash VARCHAR(60) NOT NULL, " +
                    "rules_accepted TINYINT NOT NULL DEFAULT 0, " +
                    "premium TINYINT NOT NULL DEFAULT 0, " +
                    "email VARCHAR(255) DEFAULT NULL, " +
                    "phone_number VARCHAR(20) DEFAULT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            // Sessions table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + sessionsTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "ip VARCHAR(45) NOT NULL, " +
                    "session_token VARCHAR(64) NOT NULL, " +
                    "expiry TIMESTAMP NOT NULL" +
                    ")");

            // OTP table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + otpsTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "email VARCHAR(255) NOT NULL, " +
                    "otp_code VARCHAR(6) NOT NULL, " +
                    "expires_at TIMESTAMP NOT NULL" +
                    ")");

            // Logs table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS " + logsTable + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "username VARCHAR(16) NOT NULL, " +
                    "action VARCHAR(32) NOT NULL, " +
                    "ip VARCHAR(45) NOT NULL, " +
                    "details VARCHAR(512) DEFAULT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
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

            // Check email column; add it if missing
            try (ResultSet columns = meta.getColumns(null, null, usersTable, "email")) {
                if (!columns.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + usersTable +
                                " ADD COLUMN email VARCHAR(255) DEFAULT NULL AFTER premium");
                        logger.info("Migration: added email column to " + usersTable);
                    }
                }
            }

            // Check phone_number column; add it if missing
            try (ResultSet columns = meta.getColumns(null, null, usersTable, "phone_number")) {
                if (!columns.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("ALTER TABLE " + usersTable +
                                " ADD COLUMN phone_number VARCHAR(20) DEFAULT NULL AFTER email");
                        logger.info("Migration: added phone_number column to " + usersTable);
                    }
                }
            }

            // Check idx_email index; add it if missing
            boolean hasEmailIndex = false;
            try (ResultSet indexInfo = meta.getIndexInfo(null, null, usersTable, false, false)) {
                while (indexInfo.next()) {
                    if ("idx_email".equalsIgnoreCase(indexInfo.getString("INDEX_NAME"))) {
                        hasEmailIndex = true;
                        break;
                    }
                }
            }
            if (!hasEmailIndex) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE INDEX idx_email ON " + usersTable + " (email)");
                    logger.info("Migration: created index idx_email on " + usersTable);
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

    public String getEmail(UUID uuid) {
        String query = "SELECT email FROM " + usersTable + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("email");
            }
        } catch (SQLException e) {
            logger.error("Failed to get email for UUID: " + uuid, e);
        }
        return null;
    }

    public void setEmail(UUID uuid, String email) {
        String query = "UPDATE " + usersTable + " SET email = ? WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            if (email == null || email.trim().isEmpty()) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, email.trim());
            }
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set email for UUID: " + uuid, e);
        }
    }

    public String getPhoneNumber(UUID uuid) {
        String query = "SELECT phone_number FROM " + usersTable + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("phone_number");
            }
        } catch (SQLException e) {
            logger.error("Failed to get phone number for UUID: " + uuid, e);
        }
        return null;
    }

    public void setPhoneNumber(UUID uuid, String phone) {
        String query = "UPDATE " + usersTable + " SET phone_number = ? WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            if (phone == null || phone.trim().isEmpty()) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, phone.trim());
            }
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set phone number for UUID: " + uuid, e);
        }
    }

    // ───── OTP & Admin Command Methods ───────────────────────────────────────

    public boolean saveOTP(UUID uuid, String email, String otpCode) {
        String query = "INSERT INTO " + otpsTable + " (uuid, email, otp_code, expires_at) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE email = ?, otp_code = ?, expires_at = ?";
        Timestamp expiry = new Timestamp(System.currentTimeMillis() + (5 * 60 * 1000L)); // 5 minutes
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, email);
            ps.setString(3, otpCode);
            ps.setTimestamp(4, expiry);
            ps.setString(5, email);
            ps.setString(6, otpCode);
            ps.setTimestamp(7, expiry);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to save OTP for UUID: " + uuid, e);
            return false;
        }
    }

    public String getOTP(String email) {
        String query = "SELECT otp_code, expires_at FROM " + otpsTable + " WHERE LOWER(email) = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp expires = rs.getTimestamp("expires_at");
                    if (expires.after(new Timestamp(System.currentTimeMillis()))) {
                        return rs.getString("otp_code");
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get OTP for email: " + email, e);
        }
        return null;
    }

    public boolean deleteOTP(UUID uuid) {
        String query = "DELETE FROM " + otpsTable + " WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to delete OTP for UUID: " + uuid, e);
            return false;
        }
    }

    public boolean unregisterUser(String username) {
        String query = "DELETE FROM " + usersTable + " WHERE LOWER(username) = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, username.toLowerCase());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to unregister user: " + username, e);
            return false;
        }
    }

    public boolean updatePasswordByUsername(String username, String passwordHash) {
        String query = "UPDATE " + usersTable + " SET password_hash = ? WHERE LOWER(username) = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, passwordHash);
            ps.setString(2, username.toLowerCase());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update password for user: " + username, e);
            return false;
        }
    }

    public boolean updateEmailByUsername(String username, String email) {
        String query = "UPDATE " + usersTable + " SET email = ? WHERE LOWER(username) = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            if (email == null || email.trim().isEmpty()) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, email.trim());
            }
            ps.setString(2, username.toLowerCase());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Failed to update email for user: " + username, e);
            return false;
        }
    }

    public java.util.Map<String, String> getUserInfo(String username) {
        String query = "SELECT uuid, username, premium, email, phone_number, created_at FROM " + usersTable + " WHERE LOWER(username) = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, username.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.util.Map<String, String> info = new java.util.HashMap<>();
                    info.put("uuid", rs.getString("uuid"));
                    info.put("username", rs.getString("username"));
                    info.put("premium", String.valueOf(rs.getInt("premium") == 1));
                    info.put("email", rs.getString("email") != null ? rs.getString("email") : "Tidak ada");
                    info.put("phone_number", rs.getString("phone_number") != null ? rs.getString("phone_number") : "Tidak ada");
                    info.put("created_at", rs.getTimestamp("created_at").toString());
                    return info;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get user info for: " + username, e);
        }
        return null;
    }

    public void logActivity(UUID uuid, String username, String action, String ip, String details) {
        String query = "INSERT INTO " + logsTable + " (uuid, username, action, ip, details) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, action);
            ps.setString(4, ip);
            if (details == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, details);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to log activity (" + action + ") for " + username, e);
        }
    }
}

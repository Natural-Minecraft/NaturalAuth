package id.naturalsmp.naturalauth.velocity;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.velocity.database.DatabaseManager;
import id.naturalsmp.naturalauth.velocity.listener.VelocityListener;
import id.naturalsmp.naturalauth.velocity.session.SessionManager;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "naturalauth",
        name = "NaturalAuth",
        version = "1.0-SNAPSHOT",
        description = "Cross-Platform Authentication for NaturalSMP",
        authors = {"Antigravity"}
)
public class NaturalAuthVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    
    private DatabaseManager databaseManager;
    private SessionManager sessionManager;
    private Toml config;

    public static final MinecraftChannelIdentifier BRIDGE_CHANNEL =
            MinecraftChannelIdentifier.from(AuthBridgeProtocol.FULL_CHANNEL);

    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    @Inject
    public NaturalAuthVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Load Configuration
        loadConfig();

        // Initialize Database
        databaseManager = new DatabaseManager(logger);
        Toml dbSection = config.getTable("database");
        databaseManager.init(
                dbSection.getString("host", "localhost"),
                dbSection.getLong("port", 3306L).intValue(),
                dbSection.getString("name", "nsmp_naturalauth"),
                dbSection.getString("username", "root"),
                dbSection.getString("password", ""),
                dbSection.getString("table-prefix", "naturalauth_")
        );

        // Initialize Session Manager
        Toml settingsSection = config.getTable("settings");
        sessionManager = new SessionManager(
                databaseManager,
                settingsSection.getLong("session-expiry-hours", 24L).intValue(),
                settingsSection.getBoolean("auto-login", true)
        );

        // Register Channel
        server.getChannelRegistrar().register(BRIDGE_CHANNEL);

        // Register Listeners
        server.getEventManager().register(this, new VelocityListener(this));

        logger.info("NaturalAuth Velocity Plugin has been initialized successfully!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (databaseManager != null) {
            databaseManager.close();
        }
        logger.info("NaturalAuth Velocity Plugin has been shut down.");
    }

    private void loadConfig() {
        if (!Files.exists(dataDirectory)) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException e) {
                logger.error("Could not create data directory", e);
            }
        }

        File configFile = new File(dataDirectory.toFile(), "config.toml");
        if (!configFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                } else {
                    configFile.createNewFile();
                }
            } catch (IOException e) {
                logger.error("Could not create default config file", e);
            }
        }

        config = new Toml().read(configFile);
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public Toml getConfig() {
        return config;
    }

    public Set<UUID> getAuthenticatedPlayers() {
        return authenticatedPlayers;
    }

    public Map<UUID, Long> getJoinTimes() {
        return joinTimes;
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticatedPlayers.contains(uuid);
    }

    public void setAuthenticated(UUID uuid, boolean auth) {
        if (auth) {
            authenticatedPlayers.add(uuid);
            joinTimes.remove(uuid); // Clean up join time
        } else {
            authenticatedPlayers.remove(uuid);
        }
    }

    // Password processing logic
    public boolean verifyPassword(String username, String password) {
        String hash = databaseManager.getPasswordHash(username);
        if (hash == null) return false;
        try {
            return BCrypt.checkpw(password, hash);
        } catch (Exception e) {
            logger.error("Error verifying password for " + username, e);
            return false;
        }
    }

    public boolean register(UUID uuid, String username, String password) {
        int rounds = config.getTable("settings").getLong("bcrypt-rounds", 10L).intValue();
        String salt = BCrypt.gensalt(rounds);
        String hash = BCrypt.hashpw(password, salt);
        return databaseManager.registerUser(uuid, username, hash);
    }
}

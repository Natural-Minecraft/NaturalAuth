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
import net.kyori.adventure.text.Component;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    // Players that have completed the full auth + rules flow
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();

    // Players that verified their password but still need to accept the rules
    private final Set<UUID> pendingRulesPlayers = ConcurrentHashMap.newKeySet();

    // Tracks when unauthenticated players joined (for timeout enforcement)
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    // Keep track of the success target (Survival) server's online status
    private boolean survivalOnline = true;

    @Inject
    public NaturalAuthVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

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

        Toml settingsSection = config.getTable("settings");
        sessionManager = new SessionManager(
                databaseManager,
                settingsSection.getLong("session-expiry-hours", 24L).intValue(),
                settingsSection.getBoolean("auto-login", true)
        );

        server.getChannelRegistrar().register(BRIDGE_CHANNEL);
        server.getEventManager().register(this, new VelocityListener(this));

        // Start Survival server online status checking task (runs immediately, then every 5 minutes)
        checkSurvivalStatus();
        server.getScheduler().buildTask(this, this::checkSurvivalStatus)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();

        logger.info("NaturalAuth Velocity Plugin has been initialized successfully!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (databaseManager != null) databaseManager.close();
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
                if (in != null) Files.copy(in, configFile.toPath());
                else configFile.createNewFile();
            } catch (IOException e) {
                logger.error("Could not create default config file", e);
            }
        }

        config = new Toml().read(configFile);
    }

    // ───── Auth State ────────────────────────────────────────────────────────

    public boolean isAuthenticated(UUID uuid) {
        return authenticatedPlayers.contains(uuid);
    }

    public void setAuthenticated(UUID uuid, boolean auth) {
        if (auth) {
            authenticatedPlayers.add(uuid);
            pendingRulesPlayers.remove(uuid);
            joinTimes.remove(uuid);
        } else {
            authenticatedPlayers.remove(uuid);
        }
    }

    public boolean isPendingRules(UUID uuid) {
        return pendingRulesPlayers.contains(uuid);
    }

    public void setPendingRules(UUID uuid, boolean pending) {
        if (pending) pendingRulesPlayers.add(uuid);
        else pendingRulesPlayers.remove(uuid);
    }

    // ───── Getters ───────────────────────────────────────────────────────────

    public ProxyServer getServer() { return server; }
    public Logger getLogger() { return logger; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public SessionManager getSessionManager() { return sessionManager; }
    public Toml getConfig() { return config; }
    public Set<UUID> getAuthenticatedPlayers() { return authenticatedPlayers; }
    public Map<UUID, Long> getJoinTimes() { return joinTimes; }

    // ───── Survival Status Checking ──────────────────────────────────────────

    public boolean isSurvivalOnline() {
        return survivalOnline;
    }

    public void checkSurvivalStatus() {
        String destinationName = config.getTable("servers").getString("success-target", "survival");
        server.getServer(destinationName).ifPresentOrElse(
                survival -> {
                    survival.ping().handle((ping, throwable) -> {
                        boolean online = (throwable == null && ping != null);
                        if (online != survivalOnline) {
                            survivalOnline = online;
                            if (online) {
                                logger.info("Server Survival (" + destinationName + ") is now ONLINE.");
                                redirectAuthenticatedPlayers();
                            } else {
                                logger.warn("Server Survival (" + destinationName + ") is now OFFLINE. Connection attempts are paused (status pings scheduled every 5 minutes).");
                            }
                        }
                        return null;
                    });
                },
                () -> {
                    survivalOnline = false;
                }
        );
    }

    private void redirectAuthenticatedPlayers() {
        String lobbyName = config.getTable("servers").getString("lobby", "lobby");
        String destinationName = config.getTable("servers").getString("success-target", "survival");
        
        server.getServer(destinationName).ifPresent(survival -> {
            for (Player player : server.getAllPlayers()) {
                if (isAuthenticated(player.getUniqueId())) {
                    player.getCurrentServer().ifPresent(current -> {
                        if (current.getServerInfo().getName().equalsIgnoreCase(lobbyName)) {
                            player.sendMessage(Component.text("§a§l[!] §r§aServer Survival sudah online! Mengalihkan Anda secara otomatis..."));
                            player.createConnectionRequest(survival).fireAndForget();
                        }
                    });
                }
            }
        });
    }

    // ───── Rules Config Helpers ──────────────────────────────────────────────

    public boolean isRulesEnabled() {
        Toml rulesSection = config.getTable("rules");
        return rulesSection != null && rulesSection.getBoolean("enabled", false);
    }

    public String getRulesTitle() {
        Toml r = config.getTable("rules");
        return r != null ? r.getString("title", "Server Rules ⚠") : "Server Rules ⚠";
    }

    public String getRulesContent() {
        Toml r = config.getTable("rules");
        return r != null ? r.getString("content", "Please read and accept our server rules:") : "Please read and accept our server rules:";
    }

    public List<String> getRulesList() {
        Toml r = config.getTable("rules");
        if (r == null) return List.of();
        List<String> list = r.getList("list");
        return list != null ? list : List.of();
    }

    public String getRulesToggleLabel() {
        Toml r = config.getTable("rules");
        return r != null ? r.getString("toggle-label", "I have read and agree to the server rules") : "I have read and agree to the server rules";
    }

    public String getRulesAcceptButton() {
        Toml r = config.getTable("rules");
        return r != null ? r.getString("accept-button", "I Accept") : "I Accept";
    }

    // ───── Crypto ────────────────────────────────────────────────────────────

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
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(rounds));
        return databaseManager.registerUser(uuid, username, hash);
    }
}

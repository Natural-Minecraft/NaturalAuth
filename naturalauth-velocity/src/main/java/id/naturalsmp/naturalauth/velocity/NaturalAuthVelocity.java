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
import id.naturalsmp.naturalauth.velocity.command.LogoutCommand;
import id.naturalsmp.naturalauth.velocity.command.PremiumCommand;
import id.naturalsmp.naturalauth.velocity.command.CrackedCommand;
import id.naturalsmp.naturalauth.velocity.command.LoginCommand;
import id.naturalsmp.naturalauth.velocity.command.RegisterCommand;
import id.naturalsmp.naturalauth.velocity.command.UnregisterCommand;
import id.naturalsmp.naturalauth.velocity.command.ForgotPasswordCommand;
import id.naturalsmp.naturalauth.velocity.command.EmailCommand;
import id.naturalsmp.naturalauth.velocity.command.NaturalAuthAdminCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
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
    private VelocityListener velocityListener;
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

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
        String host = "localhost";
        int port = 3306;
        String name = "nsmp_naturalauth";
        String username = "root";
        String password = "";
        String prefix = "naturalauth_";

        if (dbSection != null) {
            host = dbSection.getString("host", "localhost");
            Long p = dbSection.getLong("port");
            if (p != null) port = p.intValue();
            name = dbSection.getString("name", "nsmp_naturalauth");
            username = dbSection.getString("username", "root");
            password = dbSection.getString("password", "");
            prefix = dbSection.getString("table-prefix", "naturalauth_");
        } else {
            logger.warn("Database configuration section [database] is missing in config.toml! Using default settings.");
        }
        databaseManager.init(host, port, name, username, password, prefix);

        Toml settingsSection = config.getTable("settings");
        int sessionExpiryHours = 24;
        boolean autoLogin = true;

        if (settingsSection != null) {
            Long exp = settingsSection.getLong("session-expiry-hours");
            if (exp != null) sessionExpiryHours = exp.intValue();
            Boolean al = settingsSection.getBoolean("auto-login");
            if (al != null) autoLogin = al;
        } else {
            logger.warn("Settings configuration section [settings] is missing in config.toml! Using default settings.");
        }
        sessionManager = new SessionManager(databaseManager, sessionExpiryHours, autoLogin);

        server.getChannelRegistrar().register(BRIDGE_CHANNEL);
        velocityListener = new VelocityListener(this);
        server.getEventManager().register(this, velocityListener);

        // Register commands
        CommandManager commandManager = server.getCommandManager();

        CommandMeta logoutMeta = commandManager.metaBuilder("logout").build();
        commandManager.register(logoutMeta, new LogoutCommand(this));

        CommandMeta premiumMeta = commandManager.metaBuilder("premium").build();
        commandManager.register(premiumMeta, new PremiumCommand(this));

        CommandMeta crackedMeta = commandManager.metaBuilder("cracked").build();
        commandManager.register(crackedMeta, new CrackedCommand(this));

        CommandMeta loginMeta = commandManager.metaBuilder("login").build();
        commandManager.register(loginMeta, new LoginCommand(this));

        CommandMeta registerMeta = commandManager.metaBuilder("register").build();
        commandManager.register(registerMeta, new RegisterCommand(this));

        CommandMeta unregisterMeta = commandManager.metaBuilder("unregister").build();
        commandManager.register(unregisterMeta, new UnregisterCommand(this));

        CommandMeta forgotMeta = commandManager.metaBuilder("forgotpassword")
                .aliases("lupasandi", "resetpassword", "changepassword")
                .build();
        commandManager.register(forgotMeta, new ForgotPasswordCommand(this));

        CommandMeta emailMeta = commandManager.metaBuilder("email").build();
        commandManager.register(emailMeta, new EmailCommand(this));

        CommandMeta naMeta = commandManager.metaBuilder("na")
                .aliases("naturalauth")
                .build();
        commandManager.register(naMeta, new NaturalAuthAdminCommand(this));

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
    public VelocityListener getVelocityListener() { return velocityListener; }

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

    public java.util.concurrent.CompletableFuture<Boolean> isPremiumMojangName(String username) {
        if (username == null || !username.matches("^[a-zA-Z0-9_]{3,16}$")) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        
        String url = "https://api.mojang.com/users/profiles/minecraft/" + username;
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(3))
                .GET()
                .build();
                
        return httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() == 200)
                .exceptionally(ex -> {
                    logger.error("Failed to check Mojang API for username: " + username, ex);
                    return false;
                });
    }

    public java.util.concurrent.CompletableFuture<Boolean> sendOtpEmail(String username, String email, String otpCode) {
        String baseUrl = "https://naturalsmp.net";
        if (config != null && config.getTable("settings") != null) {
            String configUrl = config.getTable("settings").getString("website-url");
            if (configUrl != null) baseUrl = configUrl;
        }
        
        String url = baseUrl + "/api/auth/send-otp";
        String apiKey = "rahasia_super_aman_12345";
        
        String jsonPayload = String.format("{\"username\":\"%s\",\"email\":\"%s\",\"otpCode\":\"%s\"}", username, email, otpCode);
        
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(java.time.Duration.ofSeconds(8))
                .build();
                
        return httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        logger.info("Successfully sent OTP email to: " + email);
                        return true;
                    } else {
                        logger.error("Failed to send OTP email. Server status: " + response.statusCode() + ", Response: " + response.body());
                        return false;
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Error calling OTP API for: " + email, ex);
                    return false;
                });
    }
}

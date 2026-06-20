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
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
    private ReconnectionQueueManager queueManager;
    private final Map<String, Boolean> serverOnlineStatus = new ConcurrentHashMap<>();
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

    // Resource Pack Configuration
    private boolean resourcePackEnabled = false;
    private String resourcePackUrl = "";
    private byte[] resourcePackHash = null;
    private Component resourcePackPrompt = null;
    private boolean resourcePackRequired = false;

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

    // Limbo/Waiting room status tracking
    private final Set<UUID> limboPlayers = ConcurrentHashMap.newKeySet();
    private com.velocitypowered.api.scheduler.ScheduledTask survivalPingTask = null;
    private boolean limboCheckSpeedActive = false;

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
        queueManager = new ReconnectionQueueManager(this);
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
        survivalPingTask = server.getScheduler().buildTask(this, this::checkSurvivalStatus)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();

        server.getConsoleCommandSource().sendMessage(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "\n&a================================================================================\n" +
                    "&a _   _       _                             _        &e    _         _   _\n" +
                    "&a| \\ | | __ _| |_ _   _ _ __ __ _  | |   &e   / \\   _   _| |_| |__\n" +
                    "&a|  \\| |/ _` | __| | | | '__/ _` | | |   &e  / _ \\ | | | | __| '_ \\\n" +
                    "&a| |\\  | (_| | |_| |_| | | | (_| | | |   &e / ___ \\| |_| | |_| | | |\n" +
                    "&a|_| \\_|\\__,_|\\__|\\__,_|_|  \\__,_|_|_|   &e/_/   \\_\\\\__,_|\\__|_| |_|\n" +
                    "          &f>> &eNaturalAuth (Velocity) v1.0-SNAPSHOT Enabled! <<\n" +
                    "&a================================================================================\n"
                )
        );
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

        // Load resource-pack settings
        Toml rpSection = config.getTable("resource-pack");
        if (rpSection != null) {
            resourcePackEnabled = rpSection.getBoolean("enabled", false);
            resourcePackUrl = rpSection.getString("url", "");
            String hashHex = rpSection.getString("hash", "");
            if (hashHex != null && !hashHex.isEmpty()) {
                try {
                    resourcePackHash = hexStringToByteArray(hashHex.trim());
                } catch (Exception e) {
                    logger.error("Invalid SHA-1 hash in resource-pack settings: " + hashHex, e);
                    resourcePackHash = null;
                }
            } else {
                resourcePackHash = null;
            }
            String promptText = rpSection.getString("prompt", "");
            if (promptText != null && !promptText.isEmpty()) {
                resourcePackPrompt = LegacyComponentSerializer.legacyAmpersand().deserialize(promptText);
            } else {
                resourcePackPrompt = null;
            }
            resourcePackRequired = rpSection.getBoolean("required", false);
        } else {
            resourcePackEnabled = false;
            resourcePackUrl = "";
            resourcePackHash = null;
            resourcePackPrompt = null;
            resourcePackRequired = false;
        }
    }

    /**
     * Deep-reloads config.toml from disk and re-applies all runtime settings.
     * Database connection is preserved. Authenticated players are NOT cleared.
     */
    public void reloadPlugin() {
        // 1. Re-read config.toml from disk
        loadConfig();

        // 2. Re-apply session settings
        Toml settingsSection = config.getTable("settings");
        int sessionExpiryHours = 24;
        boolean autoLogin = true;
        if (settingsSection != null) {
            Long exp = settingsSection.getLong("session-expiry-hours");
            if (exp != null) sessionExpiryHours = exp.intValue();
            Boolean al = settingsSection.getBoolean("auto-login");
            if (al != null) autoLogin = al;
        }
        // Recreate SessionManager with updated settings (DB connection is reused)
        sessionManager = new SessionManager(databaseManager, sessionExpiryHours, autoLogin);

        logger.info("NaturalAuth config reloaded successfully.");
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
    public ReconnectionQueueManager getQueueManager() { return queueManager; }
    public boolean isServerOnline(String serverName) { return serverOnlineStatus.getOrDefault(serverName.toLowerCase(), false); }

    // ───── Getters ───────────────────────────────────────────────────────────
    
    public Set<UUID> getLimboPlayers() {
        return limboPlayers;
    }

    public void registerLimboPlayer(UUID uuid) {
        limboPlayers.add(uuid);
        updateSurvivalCheckInterval();
    }

    public void unregisterLimboPlayer(UUID uuid) {
        limboPlayers.remove(uuid);
        updateSurvivalCheckInterval();
    }

    public void updateSurvivalCheckInterval() {
        boolean needFast = !limboPlayers.isEmpty() || (queueManager != null && queueManager.hasQueues());
        if (needFast != limboCheckSpeedActive) {
            limboCheckSpeedActive = needFast;
            if (survivalPingTask != null) {
                survivalPingTask.cancel();
            }
            long interval = needFast ? 8L : 300L;
            
            logger.info("Survival server check interval dynamically updated. Fast mode active: " + needFast + " (" + interval + "s)");
            
            survivalPingTask = server.getScheduler().buildTask(this, this::checkSurvivalStatus)
                    .repeat(interval, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    public boolean isSurvivalOnline() {
        return survivalOnline;
    }

    public void checkSurvivalStatus() {
        String destinationName = config.getTable("servers").getString("success-target", "survival");

        // Always check and ping survival server
        server.getServer(destinationName).ifPresent(survival -> {
            survival.ping().handle((ping, throwable) -> {
                boolean online = (throwable == null && ping != null);
                boolean wasOnline = serverOnlineStatus.getOrDefault(destinationName.toLowerCase(), false);
                serverOnlineStatus.put(destinationName.toLowerCase(), online);
                survivalOnline = online;
                if (online != wasOnline) {
                    if (online) {
                        logger.info("Server Survival (" + destinationName + ") is now ONLINE.");
                        if (queueManager != null) {
                            queueManager.startProcessingIfOnline(destinationName);
                        }
                    } else {
                        logger.warn("Server Survival (" + destinationName + ") is now OFFLINE.");
                    }
                }
                return null;
            });
        });

        // Ping other queued servers if any
        if (queueManager != null && queueManager.hasQueues()) {
            for (String serverName : queueManager.getQueuedServers()) {
                if (serverName.equalsIgnoreCase(destinationName)) continue;
                if (queueManager.getTotal(serverName) == 0) continue;

                server.getServer(serverName).ifPresent(regServer -> {
                    regServer.ping().handle((ping, throwable) -> {
                        boolean online = (throwable == null && ping != null);
                        boolean wasOnline = serverOnlineStatus.getOrDefault(serverName.toLowerCase(), false);
                        serverOnlineStatus.put(serverName.toLowerCase(), online);
                        if (online != wasOnline) {
                            if (online) {
                                logger.info("Server " + serverName + " is now ONLINE.");
                                queueManager.startProcessingIfOnline(serverName);
                            } else {
                                logger.warn("Server " + serverName + " is now OFFLINE.");
                            }
                        }
                        return null;
                    });
                });
            }
        }
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

    public void logActivity(UUID uuid, String username, String action, String ip, String details) {
        server.getScheduler().buildTask(this, () -> {
            databaseManager.logActivity(uuid, username, action, ip, details);
        }).schedule();
    }

    // ───── Resource Pack ─────────────────────────────────────────────────────

    public boolean isResourcePackEnabled() {
        return resourcePackEnabled;
    }

    public void sendResourcePack(Player player) {
        if (resourcePackUrl == null || resourcePackUrl.isEmpty()) {
            return;
        }
        try {
            ResourcePackInfo.Builder builder = server.createResourcePackBuilder(resourcePackUrl);
            if (resourcePackHash != null) {
                builder.setHash(resourcePackHash);
            }
            if (resourcePackPrompt != null) {
                builder.setPrompt(resourcePackPrompt);
            }
            builder.setShouldForce(resourcePackRequired);
            
            player.sendResourcePackOffer(builder.build());
            logger.info("Sent resource pack offer to " + player.getUsername() + " (" + resourcePackUrl + ")");
        } catch (Exception e) {
            logger.error("Failed to send resource pack offer to " + player.getUsername(), e);
        }
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}

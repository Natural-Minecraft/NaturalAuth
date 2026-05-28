package id.naturalsmp.naturalauth.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;
import id.naturalsmp.naturalauth.velocity.FloodgateHelper;
import id.naturalsmp.naturalauth.velocity.BedrockAuthProvider;

import java.io.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class VelocityListener {

    private final NaturalAuthVelocity plugin;
    private BedrockAuthProvider bedrockAuthProvider;

    // Players that have a valid session and should be auto-redirected when Paper lobby is ready
    private final Set<UUID> pendingAutoLoginPlayers = ConcurrentHashMap.newKeySet();
    // Players that are pending rules but via session (auto-logged in, but rules not accepted)
    private final Set<UUID> pendingAutoRulesPlayers = ConcurrentHashMap.newKeySet();

    // ── Brute Force Protection ───────────────────────────────────────────────
    private final Map<UUID, Integer> loginAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loginCooldowns = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long COOLDOWN_DURATION_MS = 60_000L;



    public VelocityListener(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
        if (FloodgateHelper.isAvailable()) {
            try {
                this.bedrockAuthProvider = (BedrockAuthProvider) Class.forName("id.naturalsmp.naturalauth.velocity.BedrockFormHelper")
                        .getConstructor(NaturalAuthVelocity.class, VelocityListener.class)
                        .newInstance(plugin, this);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to load BedrockFormHelper dynamically", e);
            }
        }
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        return EventTask.withContinuation(continuation -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    boolean isRegistered = plugin.getDatabaseManager().isRegistered(username);
                    if (isRegistered) {
                        if (plugin.getDatabaseManager().isPremium(username)) {
                            plugin.getLogger().info("Username " + username + " is registered as premium locally. Forcing Mojang authentication.");
                            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                        }
                    } else {
                        boolean autoDetect = true;
                        if (plugin.getConfig() != null && plugin.getConfig().getTable("settings") != null) {
                            Boolean ad = plugin.getConfig().getTable("settings").getBoolean("auto-detect-premium");
                            if (ad != null) autoDetect = ad;
                        }
                        if (autoDetect) {
                            // Non-registered username. Query Mojang API to see if this is a premium username.
                            Boolean isPremium = plugin.isPremiumMojangName(username).join();
                            if (isPremium) {
                                plugin.getLogger().info("Username " + username + " is a premium Mojang account. Forcing Mojang authentication for new registration.");
                                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing pre-login for username: " + username, e);
                } finally {
                    continuation.resume();
                }
            });
        });
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        plugin.getLogger().info("Player " + player.getUsername() + " (" + ip + ") joined the proxy.");

        // ── Check for Premium Java player bypass ─────────────────────────────
        if (player.isOnlineMode()) {
            plugin.getLogger().info("Player " + player.getUsername() + " is connected in Premium mode. Bypassing login.");
            boolean registered = plugin.getDatabaseManager().isRegistered(player.getUsername());
            if (!registered) {
                plugin.register(uuid, player.getUsername(), "PREMIUM_AUTO_" + UUID.randomUUID().toString());
            }
            plugin.getDatabaseManager().setPremium(uuid, true);
            plugin.logActivity(uuid, player.getUsername(), "LOGIN_PREMIUM", ip, "Bypass otomatis akun Premium Mojang");

            // Use pending mechanism — Paper PLAYER_READY will trigger the next step
            plugin.setAuthenticated(uuid, false);
            plugin.getJoinTimes().put(uuid, System.currentTimeMillis());
            if (plugin.isRulesEnabled() && !plugin.getDatabaseManager().hasAcceptedRules(player.getUsername())) {
                plugin.getLogger().info("Player " + player.getUsername() + " premium bypass: needs to accept rules.");
                plugin.setPendingRules(uuid, true);
                pendingAutoRulesPlayers.add(uuid);
            } else {
                pendingAutoLoginPlayers.add(uuid);
                player.sendMessage(Component.text("§a§l[!] §r§aLogin premium otomatis berhasil!"));
            }
            return;
        }

        // ── Check for Bedrock/Floodgate player bypass ─────────────────────────
        boolean bypassBedrock = true;
        if (plugin.getConfig() != null && plugin.getConfig().getTable("settings") != null) {
            Boolean bb = plugin.getConfig().getTable("settings").getBoolean("bypass-bedrock-passwords");
            if (bb != null) bypassBedrock = bb;
        }
        if (bypassBedrock && FloodgateHelper.isFloodgatePlayer(uuid)) {
            plugin.getLogger().info("Player " + player.getUsername() + " is a Bedrock player. Bypassing password login.");
            boolean registered = plugin.getDatabaseManager().isRegistered(player.getUsername());
            if (!registered) {
                plugin.register(uuid, player.getUsername(), "BEDROCK_AUTO_" + UUID.randomUUID().toString());
            }
            plugin.logActivity(uuid, player.getUsername(), "LOGIN_BEDROCK", ip, "Bypass otomatis Bedrock (Geyser/Floodgate)");

            // Use pending mechanism — Paper PLAYER_READY will trigger the next step
            plugin.setAuthenticated(uuid, false);
            plugin.getJoinTimes().put(uuid, System.currentTimeMillis());
            if (plugin.isRulesEnabled() && !plugin.getDatabaseManager().hasAcceptedRules(player.getUsername())) {
                plugin.getLogger().info("Player " + player.getUsername() + " bedrock bypass: needs to accept rules.");
                plugin.setPendingRules(uuid, true);
                pendingAutoRulesPlayers.add(uuid);
            } else {
                pendingAutoLoginPlayers.add(uuid);
                player.sendMessage(Component.text("§a§l[!] §r§aAutentikasi Bedrock berhasil (Bypass password)!"));
            }
            return;
        }

        // ── Check for Auto-Login via saved session ────────────────────────────
        if (plugin.getSessionManager().checkAutoLogin(uuid, ip)) {
            plugin.logActivity(uuid, player.getUsername(), "LOGIN_SESSION", ip, "Auto-login otomatis via token sesi");
            // Check if player needs to accept rules even if session is active
            if (plugin.isRulesEnabled() && !plugin.getDatabaseManager().hasAcceptedRules(player.getUsername())) {
                plugin.getLogger().info("Player " + player.getUsername() + " auto-logged in, but needs to accept rules.");
                plugin.setAuthenticated(uuid, false);
                plugin.setPendingRules(uuid, true);
                plugin.getJoinTimes().put(uuid, System.currentTimeMillis());
                // Mark as pending auto rules — Paper PLAYER_READY will trigger rules form
                pendingAutoRulesPlayers.add(uuid);
            } else {
                plugin.getLogger().info("Player " + player.getUsername() + " has valid session — marking pendingAutoLogin.");
                plugin.setAuthenticated(uuid, false); // Keep false until Paper confirms ready
                plugin.getJoinTimes().put(uuid, System.currentTimeMillis());
                // Mark as pending auto-login — Paper PLAYER_READY will trigger finalizeAuth directly
                pendingAutoLoginPlayers.add(uuid);
                player.sendMessage(Component.text("§aAuto-login berhasil (Sesi aktif). Menghubungkan ke server..."));
            }
        } else {
            plugin.setAuthenticated(uuid, false);
            plugin.getJoinTimes().put(uuid, System.currentTimeMillis());

            // Kick player if not authenticated within 60 seconds
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (player.isActive() && !plugin.isAuthenticated(uuid)) {
                    plugin.logActivity(uuid, player.getUsername(), "TIMEOUT", ip, "Kick otomatis (Timeout 60 detik)");
                    player.disconnect(Component.text(
                        "§c§l⏱ Waktu Habis!\n" +
                        "§r§7Anda tidak menyelesaikan login dalam 60 detik.\n" +
                        "§aGabung kembali untuk mencoba lagi."
                    ));
                }
            }).delay(60, TimeUnit.SECONDS).schedule();
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isAuthenticated(player.getUniqueId())) {
            // Force redirection to Lobby/Auth server
            String lobbyName = plugin.getConfig().getTable("servers").getString("lobby", "lobby");
            RegisteredServer lobby = plugin.getServer().getServer(lobbyName).orElse(null);
            
            if (lobby != null) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(lobby));
            } else {
                plugin.getLogger().error("Lobby server '" + lobbyName + "' not found! Please check configuration.");
                player.disconnect(Component.text("§cServer Lobby tidak tersedia. Silakan hubungi admin."));
            }
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (plugin.isAuthenticated(uuid)) {
            String lobbyName = plugin.getConfig().getTable("servers").getString("lobby", "lobby");
            RegisteredServer lobby = plugin.getServer().getServer(lobbyName).orElse(null);
            if (lobby != null) {
                // Prevent disconnect by redirecting silently to Lobby (Limbo Waiting Room)
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby));
                player.sendMessage(Component.text("§c§l[!] §r§cKoneksi ke server utama terputus. Mengalihkan Anda ke ruang tunggu (Limbo)..."));
            }
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String lobbyName = plugin.getConfig().getTable("servers").getString("lobby", "lobby");
        if (event.getServer().getServerInfo().getName().equalsIgnoreCase(lobbyName)) {
            if (plugin.isAuthenticated(uuid)) {
                if (!plugin.isSurvivalOnline()) {
                    // Player connected to lobby already authenticated and survival is offline -> enter Limbo mode!
                    plugin.registerLimboPlayer(uuid);
                    sendLimboStatusToPaper(player, true);
                }
            }
        } else {
            // Player connected to a server that is not Lobby -> remove from Limbo if they were in it
            plugin.unregisterLimboPlayer(uuid);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.setAuthenticated(uuid, false);
        plugin.setPendingRules(uuid, false);
        plugin.getJoinTimes().remove(uuid);
        pendingAutoLoginPlayers.remove(uuid);
        pendingAutoRulesPlayers.remove(uuid);
        loginAttempts.remove(uuid);
        loginCooldowns.remove(uuid);
        plugin.unregisterLimboPlayer(uuid);
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isAuthenticated(player.getUniqueId())) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            if (plugin.isPendingRules(player.getUniqueId())) {
                player.sendMessage(Component.text("§cAnda harus menyetujui peraturan server terlebih dahulu!"));
            } else {
                player.sendMessage(Component.text("§cAnda harus login terlebih dahulu!"));
            }
        }
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player) {
            if (!plugin.isAuthenticated(player.getUniqueId())) {
                String cmdLine = event.getCommand().toLowerCase().trim();
                String firstWord = cmdLine.split(" ")[0];
                if (firstWord.equals("naturalauth") || firstWord.equals("na") ||
                    firstWord.equals("login") || firstWord.equals("register") ||
                    firstWord.equals("email") || firstWord.equals("forgotpassword") ||
                    firstWord.equals("lupasandi") || firstWord.equals("resetpassword") ||
                    firstWord.equals("changepassword")) {
                    return; // Allow these essential commands to bypass proxy block for auth fallbacks
                }
                
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                if (plugin.isPendingRules(player.getUniqueId())) {
                    player.sendMessage(Component.text("§cAnda harus menyetujui peraturan server terlebih dahulu!"));
                } else {
                    player.sendMessage(Component.text("§cAnda harus login terlebih dahulu!"));
                }
            }
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(NaturalAuthVelocity.BRIDGE_CHANNEL)) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        try (ByteArrayInputStream bais = new ByteArrayInputStream(event.getData());
             DataInputStream dis = new DataInputStream(bais)) {

            byte packetId = dis.readByte();
            plugin.getLogger().info("[NaturalAuth-Debug] Received PluginMessage on Velocity proxy, Packet ID: " + packetId);
            
            if (packetId == AuthBridgeProtocol.PACKET_PLAYER_READY) {
                UUID uuid = UUID.fromString(dis.readUTF());
                plugin.getLogger().info("[NaturalAuth-Debug] PACKET_PLAYER_READY uuid: " + uuid);
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    plugin.getLogger().info("[NaturalAuth-Debug] PACKET_PLAYER_READY player: " + player.getUsername()
                            + " | autoLogin=" + pendingAutoLoginPlayers.contains(uuid)
                            + " | autoRules=" + pendingAutoRulesPlayers.contains(uuid)
                            + " | auth=" + plugin.isAuthenticated(uuid));

                    if (pendingAutoLoginPlayers.remove(uuid)) {
                        // Player had a valid session — skip GUI, finalize auth directly
                        plugin.getLogger().info("[NaturalAuth-Debug] Auto-login session confirmed for " + player.getUsername() + " — finalizing auth.");
                        finalizeAuth(player);
                    } else if (pendingAutoRulesPlayers.remove(uuid)) {
                        // Player had valid session but rules not accepted yet
                        plugin.getLogger().info("[NaturalAuth-Debug] Auto-rules pending for " + player.getUsername() + " — opening rules form.");
                        if (FloodgateHelper.isFloodgatePlayer(uuid)) {
                            if (bedrockAuthProvider != null) bedrockAuthProvider.openRulesForm(player);
                        } else {
                            sendOpenRulesToPaper(player);
                        }
                    } else if (plugin.isAuthenticated(uuid)) {
                        if (plugin.getLimboPlayers().contains(uuid)) {
                            plugin.getLogger().info("[NaturalAuth-Debug] Player " + player.getUsername() + " is already authenticated and in Limbo — sending PACKET_LIMBO_STATUS (true).");
                            sendLimboStatusToPaper(player, true);
                        } else {
                            plugin.getLogger().info("[NaturalAuth-Debug] Player " + player.getUsername() + " is already authenticated and not in Limbo — sending PACKET_AUTH_STATUS (success).");
                            sendAuthStatusToPaper(player, true, "Already Authenticated");
                        }
                    } else {
                        if (plugin.isPendingRules(uuid)) {
                            // Re-open rules screen if they are pending rules (e.g. after GUI close)
                            if (!FloodgateHelper.isFloodgatePlayer(uuid)) {
                                sendOpenRulesToPaper(player);
                            }
                        } else {
                            startAuthFlow(player);
                        }
                    }
                });
            } else if (packetId == AuthBridgeProtocol.PACKET_SUBMIT_PASSWORD) {
                UUID uuid = UUID.fromString(dis.readUTF());
                String password = dis.readUTF();
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    if (!plugin.isAuthenticated(uuid)) {
                        handlePasswordSubmission(player, password);
                    }
                });
            } else if (packetId == AuthBridgeProtocol.PACKET_RULES_ACCEPTED) {
                UUID uuid = UUID.fromString(dis.readUTF());
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    if (plugin.isPendingRules(uuid)) {
                        plugin.getDatabaseManager().setRulesAccepted(uuid);
                        plugin.logActivity(uuid, player.getUsername(), "RULES_ACCEPTED", player.getRemoteAddress().getAddress().getHostAddress(), "Menyetujui peraturan server");
                        player.sendMessage(Component.text("§aAnda telah menyetujui peraturan server!"));
                        finalizeAuth(player);
                    }
                });
            } else if (packetId == AuthBridgeProtocol.PACKET_RULES_DECLINED) {
                UUID uuid = UUID.fromString(dis.readUTF());
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    if (plugin.isPendingRules(uuid)) {
                        plugin.setPendingRules(uuid, false);
                        plugin.logActivity(uuid, player.getUsername(), "RULES_DECLINED", player.getRemoteAddress().getAddress().getHostAddress(), "Menolak peraturan server");
                        player.disconnect(Component.text("§cAnda harus menyetujui peraturan untuk bermain!"));
                    }
                });
            } else if (packetId == AuthBridgeProtocol.PACKET_SUBMIT_EMAIL) {
                UUID uuid = UUID.fromString(dis.readUTF());
                String email = dis.readUTF();
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    if (!plugin.isAuthenticated(uuid)) {
                        handleEmailSubmission(player, email);
                    }
                });
            } else if (packetId == AuthBridgeProtocol.PACKET_SUBMIT_OTP) {
                UUID uuid = UUID.fromString(dis.readUTF());
                String otpCode = dis.readUTF();
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    handleOtpSubmission(player, otpCode);
                });
            }

        } catch (IOException e) {
            plugin.getLogger().error("Error handling plugin message from Paper", e);
        }
    }

    public void startAuthFlow(Player player) {
        UUID uuid = player.getUniqueId();
        boolean registered = plugin.getDatabaseManager().isRegistered(player.getUsername());

        if (FloodgateHelper.isFloodgatePlayer(uuid)) {
            // Bedrock Flow - Native GUI Form
            if (bedrockAuthProvider != null) {
                bedrockAuthProvider.openAuthForm(player, registered);
            }
        } else {
            // Java Flow - Signal Paper companion to open Anvil GUI
            sendOpenGuiToPaper(player, registered ? "LOGIN" : "REGISTER", 
                    registered ? "Masukkan Password:" : "Daftar (Password Baru):");
        }
    }

    private void handlePasswordSubmission(Player player, String password) {
        UUID uuid = player.getUniqueId();
        boolean registered = plugin.getDatabaseManager().isRegistered(player.getUsername());

        if (!registered) {
            // Registering new account
            if (password == null || password.length() < 4) {
                sendAuthStatusToPaper(player, false, "Password minimal 4 karakter!");
                return;
            }
            
            boolean success = plugin.register(uuid, player.getUsername(), password);
            if (success) {
                plugin.logActivity(uuid, player.getUsername(), "REGISTER", player.getRemoteAddress().getAddress().getHostAddress(), "Registrasi GUI berhasil");
                player.sendMessage(Component.text("§a§lNaturalAuth §r§aRegistrasi berhasil!"));
                
                // Open Email Link Prompt
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    if (player.isActive()) {
                        if (FloodgateHelper.isFloodgatePlayer(uuid)) {
                            if (bedrockAuthProvider != null) {
                                bedrockAuthProvider.openEmailLinkForm(player);
                            } else {
                                handlePasswordVerified(player);
                            }
                        } else {
                            sendOpenEmailLinkToPaper(player);
                        }
                    }
                }).delay(500, TimeUnit.MILLISECONDS).schedule();
            } else {
                plugin.logActivity(uuid, player.getUsername(), "REGISTER_FAILED", player.getRemoteAddress().getAddress().getHostAddress(), "Registrasi gagal");
                sendAuthStatusToPaper(player, false, "Registrasi gagal, coba lagi!");
            }
        } else {
            // Logging in — check cooldown first
            Long cooldownEnd = loginCooldowns.get(uuid);
            if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
                long remainingSeconds = (cooldownEnd - System.currentTimeMillis()) / 1000 + 1;
                sendAuthStatusToPaper(player, false, "Terlalu banyak percobaan! Coba lagi dalam " + remainingSeconds + "s.");
                return;
            }

            if (plugin.verifyPassword(player.getUsername(), password)) {
                loginAttempts.remove(uuid);
                loginCooldowns.remove(uuid);
                plugin.logActivity(uuid, player.getUsername(), "LOGIN", player.getRemoteAddress().getAddress().getHostAddress(), "Login GUI berhasil");
                player.sendMessage(Component.text("§aLogin berhasil!"));
                handlePasswordVerified(player);
            } else {
                int attempts = loginAttempts.merge(uuid, 1, Integer::sum);
                int remaining = MAX_LOGIN_ATTEMPTS - attempts;
                if (remaining <= 0) {
                    loginAttempts.remove(uuid);
                    loginCooldowns.put(uuid, System.currentTimeMillis() + COOLDOWN_DURATION_MS);
                    plugin.logActivity(uuid, player.getUsername(), "BRUTE_FORCE", player.getRemoteAddress().getAddress().getHostAddress(), "Terlalu banyak percobaan gagal (GUI)");
                    player.disconnect(Component.text(
                        "§c§l\uD83D\uDEAB Terlalu Banyak Percobaan!\n" +
                        "§r§7Anda gagal login sebanyak " + MAX_LOGIN_ATTEMPTS + " kali.\n" +
                        "§aSilakan coba kembali dalam 60 detik."
                    ));
                } else {
                    plugin.logActivity(uuid, player.getUsername(), "LOGIN_FAILED", player.getRemoteAddress().getAddress().getHostAddress(), "Password GUI salah (" + attempts + "/" + MAX_LOGIN_ATTEMPTS + ")");
                    sendAuthStatusToPaper(player, false, "Password salah! (" + attempts + "/" + MAX_LOGIN_ATTEMPTS + " percobaan)");
                }
            }
        }
    }

    /**
     * Text-based login via /login command — includes brute force protection.
     */
    public void handleTextLogin(Player player, String password) {
        UUID uuid = player.getUniqueId();

        // Check cooldown
        Long cooldownEnd = loginCooldowns.get(uuid);
        if (cooldownEnd != null && System.currentTimeMillis() < cooldownEnd) {
            long remainingSeconds = (cooldownEnd - System.currentTimeMillis()) / 1000 + 1;
            player.sendMessage(Component.text(
                "§c§lTerlalu banyak percobaan gagal! §r§cCoba lagi dalam §e" + remainingSeconds + " §cdetik."
            ));
            return;
        }

        if (plugin.verifyPassword(player.getUsername(), password)) {
            loginAttempts.remove(uuid);
            loginCooldowns.remove(uuid);
            plugin.logActivity(uuid, player.getUsername(), "LOGIN", player.getRemoteAddress().getAddress().getHostAddress(), "Login Command berhasil");
            player.sendMessage(Component.text("§a§lNaturalAuth §r§aLogin berhasil!"));
            handlePasswordVerified(player);
        } else {
            int attempts = loginAttempts.merge(uuid, 1, Integer::sum);
            int remaining = MAX_LOGIN_ATTEMPTS - attempts;
            if (remaining <= 0) {
                loginAttempts.remove(uuid);
                loginCooldowns.put(uuid, System.currentTimeMillis() + COOLDOWN_DURATION_MS);
                plugin.logActivity(uuid, player.getUsername(), "BRUTE_FORCE", player.getRemoteAddress().getAddress().getHostAddress(), "Terlalu banyak percobaan gagal (Command)");
                player.disconnect(Component.text(
                    "§c§l\uD83D\uDEAB Terlalu Banyak Percobaan!\n" +
                    "§r§7Anda gagal login sebanyak " + MAX_LOGIN_ATTEMPTS + " kali.\n" +
                    "§aSilakan coba kembali dalam 60 detik."
                ));
            } else {
                plugin.logActivity(uuid, player.getUsername(), "LOGIN_FAILED", player.getRemoteAddress().getAddress().getHostAddress(), "Password Command salah (" + attempts + "/" + MAX_LOGIN_ATTEMPTS + ")");
                String warningColor = remaining == 1 ? "§c§l" : "§e";
                player.sendMessage(Component.text(
                    "§cPassword salah! §7(Percobaan §c" + attempts + "§7/§c" + MAX_LOGIN_ATTEMPTS + "§7) " +
                    warningColor + (remaining == 1 ? "— Percobaan terakhir!" : "— " + remaining + " sisa")
                ));
            }
        }
    }

    public void handlePasswordVerified(Player player) {
        UUID uuid = player.getUniqueId();
        if (plugin.isRulesEnabled() && !plugin.getDatabaseManager().hasAcceptedRules(player.getUsername())) {
            plugin.setPendingRules(uuid, true);
            
            // Tell Paper auth was successful to close the Login/Register Anvil GUI
            sendAuthStatusToPaper(player, true, "Success");
            
            if (FloodgateHelper.isFloodgatePlayer(uuid)) {
                if (bedrockAuthProvider != null) bedrockAuthProvider.openRulesForm(player);
            } else {
                sendOpenRulesToPaper(player);
            }
        } else {
            finalizeAuth(player);
        }
    }

    public void finalizeAuth(Player player) {
        UUID uuid = player.getUniqueId();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        plugin.setPendingRules(uuid, false);
        plugin.setAuthenticated(uuid, true);
        loginAttempts.remove(uuid);
        loginCooldowns.remove(uuid);

        // Save Session
        plugin.getSessionManager().createSession(uuid, ip);

        // Tell Paper auth was successful (closes GUI if any)
        sendAuthStatusToPaper(player, true, "Success");

        // Redirect to success-target server after a small delay so the PACKET_AUTH_STATUS
        // plugin message is fully delivered & processed by Paper before the player transfers.
        String destinationName = plugin.getConfig().getTable("servers").getString("success-target", "survival");
        plugin.getServer().getServer(destinationName).ifPresentOrElse(
                targetServer -> {
                    plugin.getLogger().info("Scheduling redirect for " + player.getUsername() + " to " + destinationName + " in 500ms.");
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        if (player.isActive()) {
                            plugin.getLogger().info("Redirecting player " + player.getUsername() + " to " + destinationName);
                            player.createConnectionRequest(targetServer).fireAndForget();
                        }
                    }).delay(500, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
                },
                () -> {
                    plugin.getLogger().error("Target server '" + destinationName + "' not found in Velocity config! Player " + player.getUsername() + " will remain in lobby.");
                    player.sendMessage(Component.text("§c§l[!] §r§cServer tujuan tidak ditemukan. Silakan hubungi admin."));
                }
        );
    }



    // Outbound Bridge Messages
    private void sendOpenGuiToPaper(Player player, String type, String message) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            plugin.getLogger().info("[NaturalAuth-Debug] Sending PACKET_OPEN_GUI to Paper for player: " + player.getUsername() + ", type: " + type);
            dos.writeByte(AuthBridgeProtocol.PACKET_OPEN_GUI);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(type);
            dos.writeUTF(message);

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_OPEN_GUI", e);
        }
    }

    private void sendOpenRulesToPaper(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            plugin.getLogger().info("[NaturalAuth-Debug] Sending PACKET_OPEN_RULES to Paper for player: " + player.getUsername());
            dos.writeByte(AuthBridgeProtocol.PACKET_OPEN_RULES);
            dos.writeUTF(player.getUniqueId().toString());

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_OPEN_RULES", e);
        }
    }

    private void sendAuthStatusToPaper(Player player, boolean success, String message) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_AUTH_STATUS);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeBoolean(success);
            dos.writeUTF(message);

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_AUTH_STATUS", e);
        }
    }

    public void sendLimboStatusToPaper(Player player, boolean isLimbo) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_LIMBO_STATUS);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeBoolean(isLimbo);

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_LIMBO_STATUS", e);
        }
    }

    public void sendReconnectReadyToPaper(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_RECONNECT_READY);
            dos.writeUTF(player.getUniqueId().toString());

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_RECONNECT_READY", e);
        }
    }

    private void sendPluginMessage(Player player, byte[] data) {
        player.getCurrentServer().ifPresent(serverConnection -> {
            serverConnection.sendPluginMessage(NaturalAuthVelocity.BRIDGE_CHANNEL, data);
        });
    }

    private void handleEmailSubmission(Player player, String email) {
        UUID uuid = player.getUniqueId();
        if (email != null && !email.trim().isEmpty() && email.contains("@")) {
            String otpCode = String.format("%06d", new java.util.Random().nextInt(1000000));
            plugin.getDatabaseManager().setEmail(uuid, email);
            plugin.getDatabaseManager().saveOTP(uuid, email, otpCode);
            plugin.logActivity(uuid, player.getUsername(), "OTP_REQUESTED", player.getRemoteAddress().getAddress().getHostAddress(), "Mengajukan penautan email ke: " + email);
            
            player.sendMessage(Component.text("§a§lNaturalAuth §r§aMengirimkan kode OTP ke email Anda..."));
            plugin.sendOtpEmail(player.getUsername(), email, otpCode).thenAccept(success -> {
                if (success) {
                    player.sendMessage(Component.text("§aOTP telah dikirim! Cek inbox atau folder spam email Anda."));
                    sendOpenOtpToPaper(player);
                } else {
                    player.sendMessage(Component.text("§cGagal mengirim email OTP. Silakan hubungi admin atau kaitkan nanti."));
                    handlePasswordVerified(player);
                }
            });
        } else {
            player.sendMessage(Component.text("§e§lNaturalAuth §r§eEmail dilewati. Anda bisa mengaitkannya nanti jika perlu."));
            handlePasswordVerified(player);
        }
    }

    private void sendOpenEmailLinkToPaper(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            plugin.getLogger().info("[NaturalAuth-Debug] Sending PACKET_OPEN_EMAIL_LINK to Paper for player: " + player.getUsername());
            dos.writeByte(AuthBridgeProtocol.PACKET_OPEN_EMAIL_LINK);
            dos.writeUTF(player.getUniqueId().toString());

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_OPEN_EMAIL_LINK", e);
        }
    }

    public void sendOpenOtpToPaper(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            plugin.getLogger().info("[NaturalAuth-Debug] Sending PACKET_OPEN_OTP_GUI to Paper for player: " + player.getUsername());
            dos.writeByte(AuthBridgeProtocol.PACKET_OPEN_OTP_GUI);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF("Masukkan 6-Digit OTP:");

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_OPEN_OTP_GUI", e);
        }
    }

    private void handleOtpSubmission(Player player, String typedOtp) {
        UUID uuid = player.getUniqueId();
        String email = plugin.getDatabaseManager().getEmail(uuid);
        if (email == null) {
            sendAuthStatusToPaper(player, false, "Tidak ada penautan email yang aktif!");
            return;
        }

        String activeOtp = plugin.getDatabaseManager().getOTP(email);
        if (activeOtp == null) {
            sendAuthStatusToPaper(player, false, "OTP kedaluwarsa atau tidak valid!");
            return;
        }

        if (activeOtp.equals(typedOtp.trim())) {
            plugin.getDatabaseManager().deleteOTP(uuid);
            plugin.logActivity(uuid, player.getUsername(), "OTP_VERIFIED", player.getRemoteAddress().getAddress().getHostAddress(), "Email berhasil ditautkan: " + email);
            player.sendMessage(Component.text("§a§lNaturalAuth §r§aVerifikasi OTP berhasil! Email Anda telah ditautkan."));
            handlePasswordVerified(player);
        } else {
            plugin.logActivity(uuid, player.getUsername(), "OTP_FAILED", player.getRemoteAddress().getAddress().getHostAddress(), "Input OTP salah untuk email: " + email);
            sendAuthStatusToPaper(player, false, "OTP salah!");
        }
    }
}

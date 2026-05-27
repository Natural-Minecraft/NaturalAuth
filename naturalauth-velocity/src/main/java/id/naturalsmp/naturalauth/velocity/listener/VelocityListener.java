package id.naturalsmp.naturalauth.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;
import id.naturalsmp.naturalauth.velocity.FloodgateHelper;
import id.naturalsmp.naturalauth.velocity.BedrockAuthProvider;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VelocityListener {

    private final NaturalAuthVelocity plugin;
    private BedrockAuthProvider bedrockAuthProvider;

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
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        plugin.getLogger().info("Player " + player.getUsername() + " (" + ip + ") joined the proxy.");

        // Check for Auto-Login
        if (plugin.getSessionManager().checkAutoLogin(player.getUniqueId(), ip)) {
            // Check if player needs to accept rules even if session is active
            if (plugin.isRulesEnabled() && !plugin.getDatabaseManager().hasAcceptedRules(player.getUsername())) {
                plugin.getLogger().info("Player " + player.getUsername() + " auto-logged in, but needs to accept rules.");
                plugin.setAuthenticated(player.getUniqueId(), false);
                plugin.setPendingRules(player.getUniqueId(), true);
                plugin.getJoinTimes().put(player.getUniqueId(), System.currentTimeMillis());
                
                // Rules need to be accepted
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    if (FloodgateHelper.isFloodgatePlayer(player.getUniqueId())) {
                        if (bedrockAuthProvider != null) bedrockAuthProvider.openRulesForm(player);
                    } else {
                        sendOpenRulesToPaper(player);
                    }
                }).delay(1, TimeUnit.SECONDS).schedule();
            } else {
                plugin.getLogger().info("Player " + player.getUsername() + " auto-logged in via saved session.");
                plugin.setAuthenticated(player.getUniqueId(), true);
                player.sendMessage(Component.text("§aAuto-login berhasil (Sesi aktif)."));
            }
        } else {
            plugin.setAuthenticated(player.getUniqueId(), false);
            plugin.getJoinTimes().put(player.getUniqueId(), System.currentTimeMillis());
            
            // Periodically check if player timed out without authenticating
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (player.isActive() && !plugin.isAuthenticated(player.getUniqueId())) {
                    player.disconnect(Component.text("§cWaktu login habis! (Batas 60 detik)"));
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
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        plugin.setAuthenticated(player.getUniqueId(), false);
        plugin.setPendingRules(player.getUniqueId(), false);
        plugin.getJoinTimes().remove(player.getUniqueId());
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
                    plugin.getLogger().info("[NaturalAuth-Debug] PACKET_PLAYER_READY found player: " + player.getUsername() + ", auth: " + plugin.isAuthenticated(uuid));
                    if (!plugin.isAuthenticated(uuid)) {
                        if (plugin.isPendingRules(uuid)) {
                            // Re-open rules screen if they are pending rules
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
                        player.sendMessage(Component.text("§aAnda telah menyetujui peraturan server!"));
                        finalizeAuth(player);
                    }
                });
            } else if (packetId == AuthBridgeProtocol.PACKET_RULES_DECLINED) {
                UUID uuid = UUID.fromString(dis.readUTF());
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    if (plugin.isPendingRules(uuid)) {
                        plugin.setPendingRules(uuid, false);
                        player.disconnect(Component.text("§cAnda harus menyetujui peraturan untuk bermain!"));
                    }
                });
            }

        } catch (IOException e) {
            plugin.getLogger().error("Error handling plugin message from Paper", e);
        }
    }

    private void startAuthFlow(Player player) {
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
                player.sendMessage(Component.text("§aRegistrasi berhasil!"));
                handlePasswordVerified(player);
            } else {
                sendAuthStatusToPaper(player, false, "Registrasi gagal, coba lagi!");
            }
        } else {
            // Logging in
            if (plugin.verifyPassword(player.getUsername(), password)) {
                player.sendMessage(Component.text("§aLogin berhasil!"));
                handlePasswordVerified(player);
            } else {
                sendAuthStatusToPaper(player, false, "Password salah!");
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
        
        // Save Session
        plugin.getSessionManager().createSession(uuid, ip);

        // Tell Paper auth was successful (closes GUI if any)
        sendAuthStatusToPaper(player, true, "Success");

        // Redirect to success-target server
        String destinationName = plugin.getConfig().getTable("servers").getString("success-target", "survival");
        if (plugin.isSurvivalOnline()) {
            plugin.getServer().getServer(destinationName).ifPresentOrElse(
                    server -> {
                        plugin.getLogger().info("Redirecting player " + player.getUsername() + " to " + destinationName);
                        player.createConnectionRequest(server).fireAndForget();
                    },
                    () -> plugin.getLogger().error("Target server '" + destinationName + "' not found!")
            );
        } else {
            player.sendMessage(Component.text("§c§l[!] §r§cServer Survival saat ini sedang offline. Anda tetap berada di Lobby dan akan dialihkan secara otomatis begitu server online (status dicek berkala setiap 5 menit)."));
        }
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

    private void sendPluginMessage(Player player, byte[] data) {
        player.getCurrentServer().ifPresent(serverConnection -> {
            serverConnection.sendPluginMessage(NaturalAuthVelocity.BRIDGE_CHANNEL, data);
        });
    }
}

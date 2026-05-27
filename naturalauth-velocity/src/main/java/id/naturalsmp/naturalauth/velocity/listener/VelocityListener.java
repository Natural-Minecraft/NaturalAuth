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
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VelocityListener {

    private final NaturalAuthVelocity plugin;

    public VelocityListener(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        plugin.getLogger().info("Player " + player.getUsername() + " (" + ip + ") joined the proxy.");

        // Check for Auto-Login
        if (plugin.getSessionManager().checkAutoLogin(player.getUniqueId(), ip)) {
            plugin.getLogger().info("Player " + player.getUsername() + " auto-logged in via saved session.");
            plugin.setAuthenticated(player.getUniqueId(), true);
            player.sendMessage(Component.text("§aAuto-login berhasil (Sesi aktif)."));
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
        plugin.getJoinTimes().remove(player.getUniqueId());
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isAuthenticated(player.getUniqueId())) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            player.sendMessage(Component.text("§cAnda harus login terlebih dahulu!"));
        }
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (event.getSource() instanceof Player player) {
            if (!plugin.isAuthenticated(player.getUniqueId())) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                player.sendMessage(Component.text("§cAnda harus login terlebih dahulu!"));
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
            if (packetId == AuthBridgeProtocol.PACKET_PLAYER_READY) {
                UUID uuid = UUID.fromString(dis.readUTF());
                plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                    if (!plugin.isAuthenticated(uuid)) {
                        startAuthFlow(player);
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
            }

        } catch (IOException e) {
            plugin.getLogger().error("Error handling plugin message from Paper", e);
        }
    }

    private void startAuthFlow(Player player) {
        UUID uuid = player.getUniqueId();
        boolean registered = plugin.getDatabaseManager().isRegistered(player.getUsername());

        if (FloodgateApi.getInstance().isFloodgatePlayer(uuid)) {
            // Bedrock Flow - Native GUI Form
            openBedrockAuthForm(player, registered);
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
                player.sendMessage(Component.text("§aRegistrasi berhasil! Anda otomatis masuk."));
                handleSuccessfulAuth(player);
            } else {
                sendAuthStatusToPaper(player, false, "Registrasi gagal, coba lagi!");
            }
        } else {
            // Logging in
            if (plugin.verifyPassword(player.getUsername(), password)) {
                player.sendMessage(Component.text("§aLogin berhasil!"));
                handleSuccessfulAuth(player);
            } else {
                sendAuthStatusToPaper(player, false, "Password salah!");
            }
        }
    }

    private void handleSuccessfulAuth(Player player) {
        UUID uuid = player.getUniqueId();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        plugin.setAuthenticated(uuid, true);
        
        // Save Session
        plugin.getSessionManager().createSession(uuid, ip);

        // Tell Paper auth was successful (closes GUI)
        sendAuthStatusToPaper(player, true, "Success");

        // Redirect to success-target server
        String destinationName = plugin.getConfig().getTable("servers").getString("success-target", "survival");
        plugin.getServer().getServer(destinationName).ifPresentOrElse(
                server -> {
                    plugin.getLogger().info("Redirecting player " + player.getUsername() + " to " + destinationName);
                    player.createConnectionRequest(server).fireAndForget();
                },
                () -> plugin.getLogger().error("Target server '" + destinationName + "' not found!")
        );
    }

    private void openBedrockAuthForm(Player player, boolean registered) {
        UUID uuid = player.getUniqueId();
        
        if (!registered) {
            CustomForm form = CustomForm.builder()
                    .title("Registrasi Akun")
                    .input("Masukkan Password Baru (Min 4 Karakter):", "Password")
                    .input("Konfirmasi Password Baru:", "Konfirmasi Password")
                    .validResultHandler(response -> {
                        String password = response.getInput(0);
                        String confirm = response.getInput(1);
                        
                        if (password == null || password.isEmpty() || confirm == null || confirm.isEmpty()) {
                            player.sendMessage(Component.text("§cPassword tidak boleh kosong!"));
                            reopenBedrockFormDelayed(player, false);
                            return;
                        }
                        
                        if (password.length() < 4) {
                            player.sendMessage(Component.text("§cPassword minimal 4 karakter!"));
                            reopenBedrockFormDelayed(player, false);
                            return;
                        }
                        
                        if (!password.equals(confirm)) {
                            player.sendMessage(Component.text("§cKonfirmasi password tidak cocok!"));
                            reopenBedrockFormDelayed(player, false);
                            return;
                        }
                        
                        boolean success = plugin.register(uuid, player.getUsername(), password);
                        if (success) {
                            player.sendMessage(Component.text("§aRegistrasi berhasil! Anda telah masuk."));
                            handleSuccessfulAuth(player);
                        } else {
                            player.sendMessage(Component.text("§cRegistrasi gagal! Silakan coba lagi."));
                            reopenBedrockFormDelayed(player, false);
                        }
                    })
                    .closedResultHandler(() -> reopenBedrockFormDelayed(player, false))
                    .build();

            FloodgateApi.getInstance().sendForm(uuid, form);
        } else {
            CustomForm form = CustomForm.builder()
                    .title("Login Akun")
                    .input("Masukkan Password Anda:", "Password")
                    .validResultHandler(response -> {
                        String password = response.getInput(0);
                        if (password == null || password.isEmpty()) {
                            player.sendMessage(Component.text("§cPassword tidak boleh kosong!"));
                            reopenBedrockFormDelayed(player, true);
                            return;
                        }
                        
                        if (plugin.verifyPassword(player.getUsername(), password)) {
                            player.sendMessage(Component.text("§aLogin berhasil!"));
                            handleSuccessfulAuth(player);
                        } else {
                            player.sendMessage(Component.text("§cPassword salah!"));
                            reopenBedrockFormDelayed(player, true);
                        }
                    })
                    .closedResultHandler(() -> reopenBedrockFormDelayed(player, true))
                    .build();

            FloodgateApi.getInstance().sendForm(uuid, form);
        }
    }

    private void reopenBedrockFormDelayed(Player player, boolean registered) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (player.isActive() && !plugin.isAuthenticated(player.getUniqueId())) {
                openBedrockAuthForm(player, registered);
            }
        }).delay(1, TimeUnit.SECONDS).schedule();
    }

    // Outbound Bridge Messages
    private void sendOpenGuiToPaper(Player player, String type, String message) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_OPEN_GUI);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(type);
            dos.writeUTF(message);

            sendPluginMessage(player, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_OPEN_GUI", e);
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

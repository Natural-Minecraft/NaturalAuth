package id.naturalsmp.naturalauth.paper.listener;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.NaturalAuthPaper;
import id.naturalsmp.naturalauth.paper.gui.AnvilGuiRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PaperListener implements Listener, PluginMessageListener {

    private final NaturalAuthPaper plugin;
    
    // Tracks active GUI type and prompt for unauthenticated players to handle esc-reopens properly
    private final Map<UUID, String> activeGuiType = new ConcurrentHashMap<>();
    private final Map<UUID, String> activePrompt = new ConcurrentHashMap<>();

    public PaperListener(NaturalAuthPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(AuthBridgeProtocol.FULL_CHANNEL)) {
            return;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(message);
             DataInputStream dis = new DataInputStream(bais)) {

            byte packetId = dis.readByte();
            plugin.getLogger().info("[NaturalAuth-Debug] Received PluginMessage on Paper from player " + player.getName() + ", Packet ID: " + packetId);
            
            if (packetId == AuthBridgeProtocol.PACKET_OPEN_GUI) {
                UUID uuid = UUID.fromString(dis.readUTF());
                String type = dis.readUTF();
                String prompt = dis.readUTF();

                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    activeGuiType.put(uuid, type);
                    activePrompt.put(uuid, prompt);
                    if (id.naturalsmp.naturalauth.paper.gui.DialogRenderer.isDialogApiAvailable()) {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Native Dialog GUI for " + target.getName() + ", type: " + type + ", prompt: " + prompt);
                        id.naturalsmp.naturalauth.paper.gui.DialogRenderer.openDialogGUI(plugin, target, type, prompt);
                    } else {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Anvil GUI for " + target.getName() + ", type: " + type + ", prompt: " + prompt);
                        AnvilGuiRenderer.openAnvilGUI(plugin, target, type, prompt);
                    }
                }
            } else if (packetId == AuthBridgeProtocol.PACKET_AUTH_STATUS) {
                UUID uuid = UUID.fromString(dis.readUTF());
                boolean success = dis.readBoolean();
                String msg = dis.readUTF();

                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    if (success) {
                        plugin.setAuthenticated(uuid, true);
                        plugin.setPendingRules(uuid, false);
                        activeGuiType.remove(uuid);
                        activePrompt.remove(uuid);
                        target.sendMessage("§a§lNaturalAuth §r§aLogin berhasil!");
                        target.closeInventory();
                    } else {
                        target.sendMessage("§c§lNaturalAuth §r§c" + msg);
                        // Update prompt so the reopened GUI displays the error
                        activePrompt.put(uuid, msg);
                    }
                }
            } else if (packetId == AuthBridgeProtocol.PACKET_OPEN_RULES) {
                UUID uuid = UUID.fromString(dis.readUTF());
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    plugin.setPendingRules(uuid, true);
                    activeGuiType.remove(uuid);
                    activePrompt.remove(uuid);
                    target.closeInventory();
                    if (id.naturalsmp.naturalauth.paper.gui.DialogRenderer.isDialogApiAvailable()) {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Native Rules Dialog for " + target.getName());
                        id.naturalsmp.naturalauth.paper.gui.DialogRenderer.openRulesDialog(plugin, target);
                    } else {
                        plugin.getLogger().info("[NaturalAuth-Debug] Sending Chat Rules for " + target.getName());
                        sendRulesChatMessage(target);
                    }
                }
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to process incoming plugin message from Velocity!");
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        plugin.setAuthenticated(uuid, false);
        plugin.setPendingRules(uuid, false);
        AnvilGuiRenderer.clearTempPassword(uuid);

        // Teleport to lobby spawn location
        player.teleport(plugin.getSpawnLocation());

        // Notify Velocity that the player is ready to receive GUI
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendPlayerReady(player);
            }
        }, 10L); // Wait 0.5 seconds for client to load
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.setAuthenticated(uuid, false);
        plugin.setPendingRules(uuid, false);
        activeGuiType.remove(uuid);
        activePrompt.remove(uuid);
        AnvilGuiRenderer.clearTempPassword(uuid);
    }

    // Block player actions when not authenticated or pending rules
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            Location from = event.getFrom();
            Location to = event.getTo();
            
            // Allow looking around, but block physical movement coordinates
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                event.setTo(from);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
            if (plugin.isPendingRules(uuid)) {
                player.sendMessage("§cAnda harus menyetujui peraturan server terlebih dahulu!");
            } else {
                player.sendMessage("§cAnda harus login terlebih dahulu!");
            }
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            String message = event.getMessage().toLowerCase().trim();
            if (message.startsWith("/naturalauth ") || message.startsWith("naturalauth ") ||
                message.startsWith("/na ") || message.startsWith("na ") ||
                message.equals("/naturalauth") || message.equals("naturalauth") ||
                message.equals("/na") || message.equals("na")) {
                return;
            }
            event.setCancelled(true);
            if (plugin.isPendingRules(uuid)) {
                player.sendMessage("§cAnda harus menyetujui peraturan server terlebih dahulu!");
            } else {
                player.sendMessage("§cAnda harus login terlebih dahulu!");
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            // Allow only Anvil inventories for unauthenticated players, and block everything for pending rules
            if (plugin.isPendingRules(uuid)) {
                event.setCancelled(true);
            } else if (!plugin.isAuthenticated(uuid) && event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.ANVIL) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    private void sendPlayerReady(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_PLAYER_READY);
            dos.writeUTF(player.getUniqueId().toString());

            plugin.getLogger().info("[NaturalAuth-Debug] Sending PACKET_PLAYER_READY for " + player.getName() + " on channel " + AuthBridgeProtocol.FULL_CHANNEL);
            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_PLAYER_READY to Velocity!");
            e.printStackTrace();
        }
    }

    public void sendPacketRulesAccepted(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_RULES_ACCEPTED);
            dos.writeUTF(player.getUniqueId().toString());

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_RULES_ACCEPTED to Velocity!");
            e.printStackTrace();
        }
    }

    public void sendPacketRulesDeclined(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_RULES_DECLINED);
            dos.writeUTF(player.getUniqueId().toString());

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_RULES_DECLINED to Velocity!");
            e.printStackTrace();
        }
    }

    private void sendRulesChatMessage(Player player) {
        player.sendMessage(Component.text("§8§m──────────────────────────────────"));
        player.sendMessage(Component.text("§6§l    \u26A0 SERVER RULES \u26A0"));
        player.sendMessage(Component.text("§8§m──────────────────────────────────"));
        player.sendMessage(Component.text("§7Please read and accept our rules:\n"));

        player.sendMessage(Component.text("§f1. No cheating, hacking, or exploits of any kind."));
        player.sendMessage(Component.text("§f2. Be respectful to all players and staff."));
        player.sendMessage(Component.text("§f3. No spamming, advertising, or inappropriate content."));
        player.sendMessage(Component.text("§f4. Follow all staff instructions."));
        player.sendMessage(Component.text("§f5. Have fun and play fair!\n"));

        player.sendMessage(Component.text("§8§m──────────────────────────────────"));
        player.sendMessage(Component.text("§7Do you agree to these rules?"));

        var acceptButton = Component.text("§a[✔ SETUJU]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/naturalauth acceptrules"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Klik untuk menyetujui peraturan server.")));

        var declineButton = Component.text("§c[✖ TOLAK]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/naturalauth declinerules"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Klik untuk menolak peraturan (Anda akan dikick).")));

        var choiceLine = Component.text("  ")
                .append(acceptButton)
                .append(Component.text("§7 atau "))
                .append(declineButton);

        player.sendMessage(choiceLine);
        player.sendMessage(Component.text("§8§m──────────────────────────────────"));
    }

    public Map<UUID, String> getActiveGuiType() {
        return activeGuiType;
    }

    public Map<UUID, String> getActivePrompt() {
        return activePrompt;
    }
}

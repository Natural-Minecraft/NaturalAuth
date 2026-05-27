package id.naturalsmp.naturalauth.paper.listener;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.NaturalAuthPaper;
import id.naturalsmp.naturalauth.paper.gui.AnvilGuiRenderer;
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
            if (packetId == AuthBridgeProtocol.PACKET_OPEN_GUI) {
                UUID uuid = UUID.fromString(dis.readUTF());
                String type = dis.readUTF();
                String prompt = dis.readUTF();

                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    activeGuiType.put(uuid, type);
                    activePrompt.put(uuid, prompt);
                    AnvilGuiRenderer.openAnvilGUI(plugin, target, type, prompt);
                }
            } else if (packetId == AuthBridgeProtocol.PACKET_AUTH_STATUS) {
                UUID uuid = UUID.fromString(dis.readUTF());
                boolean success = dis.readBoolean();
                String msg = dis.readUTF();

                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    if (success) {
                        plugin.setAuthenticated(uuid, true);
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
        activeGuiType.remove(uuid);
        activePrompt.remove(uuid);
    }

    // Block player actions when not authenticated
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isAuthenticated(player.getUniqueId())) {
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
        if (!plugin.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§cAnda harus login terlebih dahulu!");
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§cAnda harus login terlebih dahulu!");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!plugin.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!plugin.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            // Allow only Anvil inventories for unauthenticated players
            if (!plugin.isAuthenticated(player.getUniqueId()) && event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.ANVIL) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            // Block inventory clicks unless authenticated, except for Anvil GUI input/output slots (which AnvilGUI library handles itself)
            if (!plugin.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (!plugin.isAuthenticated(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    private void sendPlayerReady(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_PLAYER_READY);
            dos.writeUTF(player.getUniqueId().toString());

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_PLAYER_READY to Velocity!");
            e.printStackTrace();
        }
    }

    public Map<UUID, String> getActiveGuiType() {
        return activeGuiType;
    }

    public Map<UUID, String> getActivePrompt() {
        return activePrompt;
    }
}

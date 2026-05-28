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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;

public class PaperListener implements Listener, PluginMessageListener {

    private final NaturalAuthPaper plugin;
    
    // Tracks active GUI type and prompt for unauthenticated players to handle esc-reopens properly
    private final Map<UUID, String> activeGuiType = new ConcurrentHashMap<>();
    private final Map<UUID, String> activePrompt = new ConcurrentHashMap<>();
    // Track admins who have a read-only Whois chest GUI open
    private final java.util.Set<UUID> whoisAdmins = ConcurrentHashMap.newKeySet();

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

                        if (id.naturalsmp.naturalauth.paper.gui.DialogRenderer.isDialogApiAvailable()) {
                            String type = activeGuiType.get(uuid);
                            if (type != null) {
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    if (target.isOnline()) {
                                        id.naturalsmp.naturalauth.paper.gui.DialogRenderer.openErrorDialog(
                                                plugin,
                                                target,
                                                type.equalsIgnoreCase("REGISTER") ? "Registrasi Gagal" : "Login Gagal",
                                                msg,
                                                () -> id.naturalsmp.naturalauth.paper.gui.DialogRenderer.openDialogGUI(plugin, target, type, msg)
                                        );
                                    }
                                }, 5L);
                            }
                        }
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
            } else if (packetId == AuthBridgeProtocol.PACKET_OPEN_EMAIL_LINK) {
                UUID uuid = UUID.fromString(dis.readUTF());
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    target.closeInventory();
                    if (id.naturalsmp.naturalauth.paper.gui.DialogRenderer.isDialogApiAvailable()) {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Native Email Link Dialog for " + target.getName());
                        id.naturalsmp.naturalauth.paper.gui.DialogRenderer.openEmailLinkDialog(plugin, target);
                    } else {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Anvil GUI for Email Link for " + target.getName());
                        AnvilGuiRenderer.openEmailLinkGUI(plugin, target);
                    }
                }
            } else if (packetId == AuthBridgeProtocol.PACKET_OPEN_OTP_GUI) {
                UUID uuid = UUID.fromString(dis.readUTF());
                String prompt = dis.readUTF();
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    target.closeInventory();
                    plugin.getLogger().info("[NaturalAuth-Debug] Opening OTP Anvil GUI for " + target.getName());
                    AnvilGuiRenderer.openOtpGUI(plugin, target, prompt);
                }
            } else if (packetId == AuthBridgeProtocol.PACKET_WHOIS_REQUEST) {
                UUID adminUuid = UUID.fromString(dis.readUTF());
                String targetUsername = dis.readUTF();
                Player admin = Bukkit.getPlayer(adminUuid);
                if (admin != null && admin.isOnline()) {
                    plugin.getLogger().info("[NaturalAuth-Debug] Opening Whois Chest GUI for admin " + admin.getName() + " targeting " + targetUsername);
                    openWhoisGui(admin, targetUsername);
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
            // Block all clicks for unauthenticated players
            if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
                event.setCancelled(true);
            }
            // Block item movement in read-only Whois GUI for admins
            else if (whoisAdmins.contains(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid) || whoisAdmins.contains(uuid)) {
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

    private void openWhoisGui(Player admin, String targetUsername) {
        // Build info items (scheduled sync because Bukkit inventory API is main-thread only)
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inv = Bukkit.createInventory(null, 27, "§8[Whois] §f" + targetUsername);

            // Helper to create a labelled info item
            java.util.function.BiFunction<String, List<String>, ItemStack> makeItem = (label, lore) -> {
                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(label);
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                }
                return item;
            };

            // ── Collect data ──────────────────────────────────────────────────
            org.bukkit.entity.Player onlineTarget = Bukkit.getPlayerExact(targetUsername);

            String uuid = onlineTarget != null ? onlineTarget.getUniqueId().toString() : "§7(offline)";
            String ip   = onlineTarget != null ? onlineTarget.getAddress() != null
                    ? onlineTarget.getAddress().getAddress().getHostAddress() : "§7N/A"
                    : "§7(offline)";
            String world    = onlineTarget != null ? onlineTarget.getWorld().getName() : "§7(offline)";
            String location = onlineTarget != null ? String.format("%.1f, %.1f, %.1f",
                    onlineTarget.getLocation().getX(),
                    onlineTarget.getLocation().getY(),
                    onlineTarget.getLocation().getZ()) : "§7(offline)";
            String pingStr  = onlineTarget != null ? onlineTarget.getPing() + " ms" : "§7(offline)";

            // ── Place items in chest ──────────────────────────────────────────
            inv.setItem(2,  makeItem.apply("§b§lUsername",    Arrays.asList("§f" + targetUsername)));
            inv.setItem(3,  makeItem.apply("§b§lUUID",        Arrays.asList("§f" + uuid)));
            inv.setItem(4,  makeItem.apply("§b§lIP Address",  Arrays.asList("§f" + ip)));
            inv.setItem(5,  makeItem.apply("§b§lPing",        Arrays.asList("§f" + pingStr)));
            inv.setItem(11, makeItem.apply("§b§lWorld",       Arrays.asList("§f" + world)));
            inv.setItem(12, makeItem.apply("§b§lLocation",    Arrays.asList("§f" + location)));

            // Status item (online/offline indicator)
            ItemStack statusItem = new ItemStack(onlineTarget != null ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta statusMeta = statusItem.getItemMeta();
            if (statusMeta != null) {
                statusMeta.setDisplayName(onlineTarget != null ? "§a§lONLINE" : "§7§lOFFLINE");
                statusItem.setItemMeta(statusMeta);
            }
            inv.setItem(13, statusItem);

            // Fill empty slots with black glass to make it look like a clean read-only GUI
            ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta fillerMeta = filler.getItemMeta();
            if (fillerMeta != null) {
                fillerMeta.setDisplayName(" ");
                filler.setItemMeta(fillerMeta);
            }
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, filler);
                }
            }

            // Mark admin as having whois GUI open (blocks item theft)
            whoisAdmins.add(admin.getUniqueId());
            admin.openInventory(inv);
        });
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            // If admin closes the whois GUI, remove the lock
            if (whoisAdmins.contains(uuid)) {
                String title = event.getView().getTitle();
                if (title.startsWith("§8[Whois]")) {
                    whoisAdmins.remove(uuid);
                }
            }
        }
    }

    public Map<UUID, String> getActiveGuiType() {
        return activeGuiType;
    }

    public Map<UUID, String> getActivePrompt() {
        return activePrompt;
    }
}

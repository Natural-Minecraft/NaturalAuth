package id.naturalsmp.naturalauth.paper.listener;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.NaturalAuthPaper;
import id.naturalsmp.naturalauth.paper.gui.AnvilGuiRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PaperListener implements Listener, PluginMessageListener {

    private final NaturalAuthPaper plugin;

    // Tracks active GUI type and prompt for unauthenticated players to handle esc-reopens properly
    private final Map<UUID, String> activeGuiType = new ConcurrentHashMap<>();
    private final Map<UUID, String> activePrompt  = new ConcurrentHashMap<>();

    // Track admins who have a read-only Whois chest GUI open
    private final Set<UUID> whoisAdmins = ConcurrentHashMap.newKeySet();

    // Track players who have the Server Selector GUI open
    private final Set<UUID> serverSelectorViewers = ConcurrentHashMap.newKeySet();

    // ── Auth UI (BossBar + ActionBar) ────────────────────────────────────────
    private final Map<UUID, BossBar> bossBars       = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> actionBarTaskIds = new ConcurrentHashMap<>();
    private final Map<UUID, Long>    loginStartTimes  = new ConcurrentHashMap<>();
    private static final int LOGIN_TIMEOUT_SECONDS = 60;

    // ── Limbo UI (BossBar + Particles + Chime) ───────────────────────────────
    private final Map<UUID, BossBar> limboBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> limboTaskIds  = new ConcurrentHashMap<>();

    // ── Server Selector item identifier ─────────────────────────────────────
    private static final String SELECTOR_NAME = "§b§lServer Selector §7(Right-Click)";

    public PaperListener(NaturalAuthPaper plugin) {
        this.plugin = plugin;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Plugin Message Handler
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(AuthBridgeProtocol.FULL_CHANNEL)) {
            return;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(message);
             DataInputStream dis = new DataInputStream(bais)) {

            byte packetId = dis.readByte();
            // plugin.getLogger().info("[NaturalAuth-Debug] Received PluginMessage on Paper from player "
            //         + player.getName() + ", Packet ID: " + packetId);

            if (packetId == AuthBridgeProtocol.PACKET_OPEN_GUI) {
                UUID uuid   = UUID.fromString(dis.readUTF());
                String type   = dis.readUTF();
                String prompt = dis.readUTF();
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    activeGuiType.put(uuid, type);
                    activePrompt.put(uuid, prompt);
                    if (id.naturalsmp.naturalauth.paper.gui.DialogRenderer.isDialogApiAvailable()) {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Native Dialog GUI for "
                                + target.getName() + ", type: " + type + ", prompt: " + prompt);
                        id.naturalsmp.naturalauth.paper.gui.DialogRenderer.openDialogGUI(plugin, target, type, prompt);
                    } else {
                        plugin.getLogger().info("[NaturalAuth-Debug] Opening Anvil GUI for "
                                + target.getName() + ", type: " + type + ", prompt: " + prompt);
                        AnvilGuiRenderer.openAnvilGUI(plugin, target, type, prompt);
                    }
                }

            } else if (packetId == AuthBridgeProtocol.PACKET_AUTH_STATUS) {
                UUID uuid    = UUID.fromString(dis.readUTF());
                boolean success = dis.readBoolean();
                String msg   = dis.readUTF();
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    if (success) {
                        plugin.setAuthenticated(uuid, true);
                        plugin.setPendingRules(uuid, false);
                        activeGuiType.remove(uuid);
                        activePrompt.remove(uuid);
                        stopAuthUI(uuid);
                        target.closeInventory();
                        
                        if ("Already Authenticated".equalsIgnoreCase(msg)) {
                            target.sendMessage("§a§lNaturalAuth §r§aSesi aktif terdeteksi. Anda telah terautentikasi otomatis.");
                        } else {
                            target.sendMessage("§a§lNaturalAuth §r§aLogin berhasil!");
                            // Success Title & Sound
                            target.showTitle(Title.title(
                                Component.text("§a✔ Login Berhasil!"),
                                Component.text("§7Selamat datang kembali, §f" + target.getName() + "§7!"),
                                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))
                            ));
                            target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                        }
                        
                        // Force SURVIVAL gamemode + give selector in lobby mode
                        if (plugin.isLobbyMode()) {
                            target.setGameMode(GameMode.SURVIVAL);
                            giveServerSelector(target);
                        }
                    } else {
                        target.sendMessage("§c§lNaturalAuth §r§c" + msg);
                        activePrompt.put(uuid, msg);
                        target.playSound(target.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
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
                UUID uuid   = UUID.fromString(dis.readUTF());
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
                UUID uuid   = UUID.fromString(dis.readUTF());
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
                UUID uuid    = UUID.fromString(dis.readUTF());
                String prompt = dis.readUTF();
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    target.closeInventory();
                    plugin.getLogger().info("[NaturalAuth-Debug] Opening OTP Anvil GUI for " + target.getName());
                    AnvilGuiRenderer.openOtpGUI(plugin, target, prompt);
                }

            } else if (packetId == AuthBridgeProtocol.PACKET_WHOIS_REQUEST) {
                UUID adminUuid       = UUID.fromString(dis.readUTF());
                String targetUsername = dis.readUTF();
                Player admin = Bukkit.getPlayer(adminUuid);
                if (admin != null && admin.isOnline()) {
                    plugin.getLogger().info("[NaturalAuth-Debug] Opening Whois Chest GUI for admin "
                            + admin.getName() + " targeting " + targetUsername);
                    openWhoisGui(admin, targetUsername);
                }

            } else if (packetId == AuthBridgeProtocol.PACKET_LIMBO_STATUS) {
                // ── Limbo Mode: Player redirected from crashed server ────────────
                UUID uuid    = UUID.fromString(dis.readUTF());
                boolean isLimbo = dis.readBoolean();
                Player target   = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    plugin.setLimbo(uuid, isLimbo);
                    if (isLimbo) {
                        plugin.setAuthenticated(uuid, true);
                        plugin.setPendingRules(uuid, false);
                        stopAuthUI(uuid);
                        // Must run on main thread
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (target.isOnline()) {
                                target.setGameMode(GameMode.SURVIVAL);
                                target.closeInventory();
                                target.teleport(plugin.getSpawnLocation());
                                giveServerSelector(target);
                            }
                        });
                        startLimboUI(target);
                    } else {
                        stopLimboUI(uuid);
                    }
                }

            } else if (packetId == AuthBridgeProtocol.PACKET_RECONNECT_READY) {
                // ── Reconnect: Main server is back online ───────────────────────
                UUID uuid   = UUID.fromString(dis.readUTF());
                Player target = Bukkit.getPlayer(uuid);
                if (target != null && target.isOnline()) {
                    plugin.setLimbo(uuid, false);
                    stopLimboUI(uuid);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (target.isOnline()) {
                            target.showTitle(Title.title(
                                Component.text("§a§l🚀 REKONEKSI!"),
                                Component.text("§7Menghubungkan kembali ke server utama..."),
                                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(800))
                            ));
                            target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
                            target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
                        }
                    });
                }
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Failed to process incoming plugin message from Velocity!");
            e.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Join / Quit
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!plugin.isLobbyMode()) {
            plugin.setAuthenticated(uuid, true);
            plugin.setPendingRules(uuid, false);
            return;
        }

        plugin.setAuthenticated(uuid, false);
        plugin.setPendingRules(uuid, false);
        plugin.setLimbo(uuid, false);
        AnvilGuiRenderer.clearTempPassword(uuid);

        // Force SURVIVAL gamemode in lobby
        player.setGameMode(GameMode.SURVIVAL);

        // Teleport to lobby spawn location
        player.teleport(plugin.getSpawnLocation());

        // Notify Velocity that the player is ready to receive GUI
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendPlayerReady(player);
            }
        }, 10L);

        // Start BossBar + ActionBar auth UI after 2 seconds (allows auto-login to complete first)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.isAuthenticated(uuid) && !plugin.isPendingRules(uuid)) {
                startAuthUI(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.setAuthenticated(uuid, false);
        plugin.setPendingRules(uuid, false);
        plugin.setLimbo(uuid, false);
        activeGuiType.remove(uuid);
        activePrompt.remove(uuid);
        AnvilGuiRenderer.clearTempPassword(uuid);
        stopAuthUI(uuid);
        stopLimboUI(uuid);
        serverSelectorViewers.remove(uuid);
        whoisAdmins.remove(uuid);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Server Selector Compass
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gives the player a premium Server Selector Compass in hotbar slot 4 (the middle slot).
     */
    private void giveServerSelector(Player player) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SELECTOR_NAME);
            List<String> lore = new ArrayList<>();
            lore.add("§7Klik kanan untuk memilih server tujuan.");
            lore.add(" ");
            lore.add("§8⚡ NaturalAuth Server Selector");
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            compass.setItemMeta(meta);
        }
        // Slot 4 is the 5th hotbar slot (middle of the 9-slot hotbar)
        player.getInventory().setItem(4, compass);
    }

    private boolean isSelectorCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && SELECTOR_NAME.equals(meta.getDisplayName());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.isAuthenticated(uuid)) return;

        ItemStack item = event.getItem();
        if (!isSelectorCompass(item)) return;

        org.bukkit.event.block.Action action = event.getAction();
        if (action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> openServerSelectorGUI(player));
        }
    }

    private void openServerSelectorGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8⚡ Server Selector ⚡");

        // Black glass pane filler
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        // Slot 11 — School Server
        ItemStack schoolItem = new ItemStack(Material.BOOKSHELF);
        ItemMeta schoolMeta = schoolItem.getItemMeta();
        if (schoolMeta != null) {
            schoolMeta.setDisplayName("§a§l🏫 School Server");
            schoolMeta.setLore(Arrays.asList("§7Klik untuk bergabung ke", "§fserver sekolah."));
            schoolItem.setItemMeta(schoolMeta);
        }
        inv.setItem(11, schoolItem);

        // Slot 13 — Survival Server
        ItemStack survivalItem = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta survivalMeta = survivalItem.getItemMeta();
        if (survivalMeta != null) {
            survivalMeta.setDisplayName("§e§l🌲 Survival Server");
            survivalMeta.setLore(Arrays.asList("§7Klik untuk bergabung ke", "§fserver survival utama."));
            survivalItem.setItemMeta(survivalMeta);
        }
        inv.setItem(13, survivalItem);

        // Slot 15 — Lobby
        ItemStack lobbyItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta lobbyMeta = lobbyItem.getItemMeta();
        if (lobbyMeta != null) {
            lobbyMeta.setDisplayName("§d§l🌌 Lobby");
            lobbyMeta.setLore(Arrays.asList("§7Klik untuk kembali ke", "§flobby / ruang tunggu."));
            lobbyItem.setItemMeta(lobbyMeta);
        }
        inv.setItem(15, lobbyItem);

        serverSelectorViewers.add(player.getUniqueId());
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.3f);
    }

    /**
     * Sends a BungeeCord Connect message to redirect the player to another server.
     */
    private void transferToServer(Player player, String serverName) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF("Connect");
            dos.writeUTF(serverName);
            player.sendPluginMessage(plugin, "BungeeCord", baos.toByteArray());
            plugin.getLogger().info("[NaturalAuth] Sending BungeeCord Connect for "
                    + player.getName() + " → " + serverName);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send BungeeCord Connect for " + player.getName());
            e.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Protection Events
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
            return;
        }
        // Prevent dropping the compass selector
        if (isSelectorCompass(event.getItemDrop().getItemStack())) {
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
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            Location from = event.getFrom();
            Location to   = event.getTo();
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
                message.startsWith("/na ")          || message.startsWith("na ") ||
                message.equals("/naturalauth")      || message.equals("naturalauth") ||
                message.equals("/na")               || message.equals("na")) {
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
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (plugin.isPendingRules(uuid)) {
                event.setCancelled(true);
            } else if (!plugin.isAuthenticated(uuid)
                    && event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.ANVIL) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        // Block all clicks for unauthenticated / pending rules players
        if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) {
            event.setCancelled(true);
            return;
        }

        // Block item movement in read-only Whois GUI
        if (whoisAdmins.contains(uuid)) {
            event.setCancelled(true);
            return;
        }

        // Handle Server Selector GUI interactions
        if (serverSelectorViewers.contains(uuid)) {
            event.setCancelled(true);
            String title = event.getView().getTitle();
            if (!"§8⚡ Server Selector ⚡".equals(title)) return;

            int slot = event.getRawSlot();
            if (slot == 11) {
                player.closeInventory();
                player.sendMessage("§a§l🏫 §r§aMemindahkan ke §fSchool Server§a...");
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> transferToServer(player, "school"), 5L);
            } else if (slot == 13) {
                player.closeInventory();
                player.sendMessage("§e§l🌲 §r§eMemindahkan ke §fSurvival Server§e...");
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> transferToServer(player, "survival"), 5L);
            } else if (slot == 15) {
                player.closeInventory();
                player.sendMessage("§d§l🌌 §r§dAnda sudah berada di Lobby!");
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.0f);
            }
            return;
        }

        // Prevent moving the selector compass out of the player inventory
        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursorItem  = event.getCursor();
        if (isSelectorCompass(currentItem) || isSelectorCompass(cursorItem)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (!plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)
                    || whoisAdmins.contains(uuid) || serverSelectorViewers.contains(uuid)) {
                event.setCancelled(true);
                return;
            }
            // Prevent dragging the selector compass
            if (event.getOldCursor() != null && isSelectorCompass(event.getOldCursor())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            UUID uuid  = player.getUniqueId();
            String title = event.getView().getTitle();
            if (whoisAdmins.contains(uuid) && title.startsWith("§8[Whois]")) {
                whoisAdmins.remove(uuid);
            }
            if (serverSelectorViewers.contains(uuid) && "§8⚡ Server Selector ⚡".equals(title)) {
                serverSelectorViewers.remove(uuid);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Limbo UI
    // ═══════════════════════════════════════════════════════════════════════════

    private void startLimboUI(Player player) {
        UUID uuid = player.getUniqueId();
        stopLimboUI(uuid); // Cancel any prior limbo task

        // Create purple Limbo BossBar
        BossBar bossBar = Bukkit.createBossBar(
            "§d§l🌌 LIMBO 🌌 §8| §fServer utama sedang bersiap... §7[Menunggu]",
            BarColor.PURPLE,
            BarStyle.SEGMENTED_10
        );
        bossBar.addPlayer(player);
        bossBar.setProgress(1.0);
        limboBossBars.put(uuid, bossBar);

        // ── Announcement chat message ─────────────────────────────────────────
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§d§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            player.sendMessage("§d§l  🌌 LIMBO — Ruang Tunggu 🌌");
            player.sendMessage("§8│ §7Server utama sedang offline atau restart.");
            player.sendMessage("§8│ §7Anda akan otomatis dihubungkan kembali");
            player.sendMessage("§8│ §7ketika server siap.");
            player.sendMessage("§8│");
            player.sendMessage("§8│ §bGunakan §f🧭 Kompas §bdi slot ke-5 hotbar");
            player.sendMessage("§8│ §buntuk menjelajahi server lain.");
            player.sendMessage("§d§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            player.showTitle(Title.title(
                Component.text("§d§l🌌 LIMBO"),
                Component.text("§7Menunggu server utama..."),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))
            ));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
        });

        // ── Repeating ambient task (particles + chime + actionbar) ───────────
        final int[] tickCounter = {0};
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!player.isOnline() || !plugin.isInLimbo(uuid)) {
                stopLimboUI(uuid);
                return;
            }
            tickCounter[0]++;

            // Aesthetic particles around the player
            Location loc = player.getLocation().add(0, 1.0, 0);
            player.spawnParticle(Particle.WITCH, loc, 3, 0.4, 0.5, 0.4, 0.01);
            player.spawnParticle(Particle.PORTAL,     loc, 5, 0.3, 0.8, 0.3, 0.05);

            // Amethyst chime every 4 seconds (80 ticks at 20 TPS)
            if (tickCounter[0] % 80 == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                    0.5f, 0.8f + (float)(Math.random() * 0.5));
            }

            // Animated BossBar title flicker (every 40 ticks)
            BossBar bar = limboBossBars.get(uuid);
            if (bar != null) {
                boolean blink = (tickCounter[0] % 40) < 20;
                bar.setTitle(blink
                    ? "§d§l🌌 LIMBO 🌌 §8| §fServer utama sedang bersiap... §7[Menunggu]"
                    : "§5§l🌌 LIMBO 🌌 §8| §7Mohon bersabar... ⌛");
            }

            // Rotate ActionBar hints every 5 seconds (100 ticks)
            int hint = (tickCounter[0] / 100) % 3;
            String actionBarText = switch (hint) {
                case 0  -> "§d🌌 §7Anda sedang di §dLimbo §7— Server utama akan segera aktif!";
                case 1  -> "§b🧭 §7Klik kanan §bKompas §7untuk memilih server lain.";
                default -> "§e⌛ §7Mohon tunggu... Proxy sedang memantau server utama.";
            };
            player.sendActionBar(Component.text(actionBarText));

        }, 0L, 5L); // every 5 ticks (0.25 s) for smooth particles
        limboTaskIds.put(uuid, taskId);
    }

    private void stopLimboUI(UUID uuid) {
        BossBar bar = limboBossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
        Integer taskId = limboTaskIds.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Auth UI
    // ═══════════════════════════════════════════════════════════════════════════

    private void startAuthUI(Player player) {
        UUID uuid = player.getUniqueId();
        if (plugin.isAuthenticated(uuid) || plugin.isPendingRules(uuid)) return;

        loginStartTimes.put(uuid, System.currentTimeMillis());

        BossBar bossBar = Bukkit.createBossBar(
            "§a§l⏱ Login §8| §f" + LOGIN_TIMEOUT_SECONDS + " §adetik tersisa",
            BarColor.GREEN,
            BarStyle.SOLID
        );
        bossBar.addPlayer(player);
        bossBar.setProgress(1.0);
        bossBars.put(uuid, bossBar);

        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!player.isOnline() || plugin.isAuthenticated(uuid)) {
                stopAuthUI(uuid);
                return;
            }
            long elapsedSeconds = (System.currentTimeMillis()
                    - loginStartTimes.getOrDefault(uuid, System.currentTimeMillis())) / 1000;

            // Periodically request Velocity to verify auth status every 5 seconds (self-healing / state-sync)
            if (elapsedSeconds % 5 == 0) {
                sendPacketStatusCheck(player);
            }

            int remaining = (int) Math.max(0, LOGIN_TIMEOUT_SECONDS - elapsedSeconds);
            if (remaining <= 0) {
                player.kick(Component.text(
                    "§c§l⏱ Waktu Habis!\n" +
                    "§r§7Anda tidak menyelesaikan login dalam 60 detik.\n" +
                    "§aGabung kembali untuk mencoba lagi."
                ));
                stopAuthUI(uuid);
                return;
            }
            double progress = remaining / (double) LOGIN_TIMEOUT_SECONDS;

            BossBar bar = bossBars.get(uuid);
            if (bar != null) {
                String timeColor;
                BarColor barColor;
                if (remaining <= 10) {
                    timeColor = "§c"; barColor = BarColor.RED;
                } else if (remaining <= 20) {
                    timeColor = "§e"; barColor = BarColor.YELLOW;
                } else {
                    timeColor = "§a"; barColor = BarColor.GREEN;
                }
                bar.setTitle("§e§l⏱ Login §8| " + timeColor + remaining + " §edetik tersisa");
                bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                bar.setColor(barColor);
            }

            boolean alternate = (elapsedSeconds % 8) >= 4;
            String hint = alternate
                ? "§7Belum punya akun? Gunakan §f/register§7 di chat"
                : "§e🔑 Gunakan §fGUI §eyang muncul §eatau ketik §f/login <password>";
            player.sendActionBar(Component.text(hint));

        }, 0L, 20L);
        actionBarTaskIds.put(uuid, taskId);
    }

    private void stopAuthUI(UUID uuid) {
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
        Integer taskId = actionBarTaskIds.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        loginStartTimes.remove(uuid);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Plugin Message Senders (Paper → Velocity)
    // ═══════════════════════════════════════════════════════════════════════════

    private void sendPlayerReady(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_PLAYER_READY);
            dos.writeUTF(player.getUniqueId().toString());

            plugin.getLogger().info("[NaturalAuth-Debug] Sending PACKET_PLAYER_READY for "
                    + player.getName() + " on channel " + AuthBridgeProtocol.FULL_CHANNEL);
            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_PLAYER_READY to Velocity!");
            e.printStackTrace();
        }
    }

    public void sendPacketStatusCheck(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_STATUS_CHECK);
            dos.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_STATUS_CHECK to Velocity!");
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Rules Chat Fallback
    // ═══════════════════════════════════════════════════════════════════════════

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
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("Klik untuk menyetujui peraturan server.")));

        var declineButton = Component.text("§c[✖ TOLAK]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/naturalauth declinerules"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("Klik untuk menolak peraturan (Anda akan dikick).")));

        player.sendMessage(Component.text("  ").append(acceptButton)
                .append(Component.text("§7 atau ")).append(declineButton));
        player.sendMessage(Component.text("§8§m──────────────────────────────────"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Whois GUI (Admin)
    // ═══════════════════════════════════════════════════════════════════════════

    private void openWhoisGui(Player admin, String targetUsername) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inv = Bukkit.createInventory(null, 27, "§8[Whois] §f" + targetUsername);

            java.util.function.BiFunction<String, List<String>, ItemStack> makeItem = (label, lore) -> {
                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) { meta.setDisplayName(label); meta.setLore(lore); item.setItemMeta(meta); }
                return item;
            };

            org.bukkit.entity.Player onlineTarget = Bukkit.getPlayerExact(targetUsername);
            String uuid     = onlineTarget != null ? onlineTarget.getUniqueId().toString() : "§7(offline)";
            String ip       = onlineTarget != null && onlineTarget.getAddress() != null
                    ? onlineTarget.getAddress().getAddress().getHostAddress() : "§7(offline)";
            String world    = onlineTarget != null ? onlineTarget.getWorld().getName() : "§7(offline)";
            String location = onlineTarget != null ? String.format("%.1f, %.1f, %.1f",
                    onlineTarget.getLocation().getX(), onlineTarget.getLocation().getY(),
                    onlineTarget.getLocation().getZ()) : "§7(offline)";
            String pingStr  = onlineTarget != null ? onlineTarget.getPing() + " ms" : "§7(offline)";

            inv.setItem(2,  makeItem.apply("§b§lUsername",   Arrays.asList("§f" + targetUsername)));
            inv.setItem(3,  makeItem.apply("§b§lUUID",       Arrays.asList("§f" + uuid)));
            inv.setItem(4,  makeItem.apply("§b§lIP Address", Arrays.asList("§f" + ip)));
            inv.setItem(5,  makeItem.apply("§b§lPing",       Arrays.asList("§f" + pingStr)));
            inv.setItem(11, makeItem.apply("§b§lWorld",      Arrays.asList("§f" + world)));
            inv.setItem(12, makeItem.apply("§b§lLocation",   Arrays.asList("§f" + location)));

            ItemStack statusItem = new ItemStack(onlineTarget != null ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta statusMeta = statusItem.getItemMeta();
            if (statusMeta != null) {
                statusMeta.setDisplayName(onlineTarget != null ? "§a§lONLINE" : "§7§lOFFLINE");
                statusItem.setItemMeta(statusMeta);
            }
            inv.setItem(13, statusItem);

            ItemStack fillerItem = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta fillerMeta = fillerItem.getItemMeta();
            if (fillerMeta != null) { fillerMeta.setDisplayName(" "); fillerItem.setItemMeta(fillerMeta); }
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) == null) inv.setItem(i, fillerItem);
            }

            whoisAdmins.add(admin.getUniqueId());
            admin.openInventory(inv);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<UUID, String> getActiveGuiType() { return activeGuiType; }
    public Map<UUID, String> getActivePrompt()  { return activePrompt; }
}

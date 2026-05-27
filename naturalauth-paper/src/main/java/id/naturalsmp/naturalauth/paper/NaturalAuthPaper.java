package id.naturalsmp.naturalauth.paper;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.listener.PaperListener;
import id.naturalsmp.naturalauth.paper.schematic.SchematicLoader;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NaturalAuthPaper extends JavaPlugin {

    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingRulesPlayers = ConcurrentHashMap.newKeySet();
    private PaperListener listener;
    private Location spawnLocation;
    private Location schematicPasteLocation;
    private boolean enableSchematicLoading;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // Register plugin messaging channels
        getServer().getMessenger().registerOutgoingPluginChannel(this, AuthBridgeProtocol.FULL_CHANNEL);
        this.listener = new PaperListener(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, AuthBridgeProtocol.FULL_CHANNEL, listener);

        // Register events
        getServer().getPluginManager().registerEvents(listener, this);

        // Register commands
        if (getCommand("naturalauth") != null) {
            getCommand("naturalauth").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("Hanya pemain yang dapat menjalankan perintah ini!");
                    return true;
                }
                
                UUID uuid = player.getUniqueId();
                if (args.length > 0) {
                    String sub = args[0].toLowerCase();
                    if (sub.equals("acceptrules")) {
                        if (isPendingRules(uuid)) {
                            listener.sendPacketRulesAccepted(player);
                            setPendingRules(uuid, false);
                            setAuthenticated(uuid, true);
                        } else {
                            player.sendMessage("§cAnda tidak sedang dalam antrean persetujuan peraturan!");
                        }
                        return true;
                    } else if (sub.equals("declinerules")) {
                        if (isPendingRules(uuid)) {
                            listener.sendPacketRulesDeclined(player);
                            setPendingRules(uuid, false);
                            player.kick(net.kyori.adventure.text.Component.text("§cAnda harus menyetujui peraturan untuk bermain!"));
                        } else {
                            player.sendMessage("§cAnda tidak sedang dalam antrean persetujuan peraturan!");
                        }
                        return true;
                    }
                }
                player.sendMessage("§cUsage: /naturalauth <acceptrules|declinerules>");
                return true;
            });
        }

        // Load schematic
        if (enableSchematicLoading) {
            File structureFile = new File(getDataFolder(), "lobby.nbt");
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            SchematicLoader.loadLobbyStructure(getLogger(), structureFile, schematicPasteLocation);
        }

        getLogger().info("NaturalAuth Paper companion has been enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getLogger().info("NaturalAuth Paper companion has been disabled.");
    }

    private void loadConfigValues() {
        enableSchematicLoading = getConfig().getBoolean("enable-schematic-loading", true);

        // Read spawn location
        String spawnWorldName = getConfig().getString("spawn-location.world", "world");
        double spawnX = getConfig().getDouble("spawn-location.x", 0.5);
        double spawnY = getConfig().getDouble("spawn-location.y", 102.0);
        double spawnZ = getConfig().getDouble("spawn-location.z", 0.5);
        float spawnYaw = (float) getConfig().getDouble("spawn-location.yaw", 0.0);
        float spawnPitch = (float) getConfig().getDouble("spawn-location.pitch", 0.0);

        World spawnWorld = Bukkit.getWorld(spawnWorldName);
        if (spawnWorld == null) {
            spawnWorld = Bukkit.getWorlds().get(0);
        }
        spawnLocation = new Location(spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);

        // Read schematic paste location
        String pasteWorldName = getConfig().getString("schematic-paste.world", "world");
        double pasteX = getConfig().getDouble("schematic-paste.x", 0.0);
        double pasteY = getConfig().getDouble("schematic-paste.y", 100.0);
        double pasteZ = getConfig().getDouble("schematic-paste.z", 0.0);

        World pasteWorld = Bukkit.getWorld(pasteWorldName);
        if (pasteWorld == null) {
            pasteWorld = Bukkit.getWorlds().get(0);
        }
        schematicPasteLocation = new Location(pasteWorld, pasteX, pasteY, pasteZ);
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticatedPlayers.contains(uuid);
    }

    public void setAuthenticated(UUID uuid, boolean auth) {
        if (auth) {
            authenticatedPlayers.add(uuid);
            pendingRulesPlayers.remove(uuid);
        } else {
            authenticatedPlayers.remove(uuid);
        }
    }

    public boolean isPendingRules(UUID uuid) {
        return pendingRulesPlayers.contains(uuid);
    }

    public void setPendingRules(UUID uuid, boolean pending) {
        if (pending) {
            pendingRulesPlayers.add(uuid);
            authenticatedPlayers.remove(uuid); // Safety check
        } else {
            pendingRulesPlayers.remove(uuid);
        }
    }

    public Set<UUID> getAuthenticatedPlayers() {
        return authenticatedPlayers;
    }

    public Set<UUID> getPendingRulesPlayers() {
        return pendingRulesPlayers;
    }

    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    public PaperListener getListener() {
        return listener;
    }
}

package id.naturalsmp.naturalauth.paper.schematic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.logging.Logger;

public class SchematicLoader {

    public static void loadLobbyStructure(Logger logger, File file, Location location) {
        if (!file.exists()) {
            logger.warning("lobby.nbt not found in plugin folder. Generating fallback bedrock platform at " + location.toVector());
            generateFallbackPlatform(location);
            return;
        }

        World world = location.getWorld();
        if (world == null) {
            logger.severe("Invalid world for lobby pasting!");
            return;
        }

        logger.info("Loading lobby structure from " + file.getName() + "...");
        StructureManager sm = Bukkit.getStructureManager();
        try {
            Structure structure = sm.loadStructure(file);
            structure.place(location, true, org.bukkit.block.structure.StructureRotation.NONE, org.bukkit.block.structure.Mirror.NONE, 0, 1.0f, new Random());
            logger.info("Lobby structure placed successfully.");
        } catch (IOException e) {
            logger.severe("Failed to load lobby structure file!");
            e.printStackTrace();
            logger.warning("Generating fallback bedrock platform due to structure loading error.");
            generateFallbackPlatform(location);
        }
    }

    private static void generateFallbackPlatform(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        int radius = 4;
        int y = loc.getBlockY() - 1;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Generate a sleek bedrock platform with glowstone corners
                Location blockLoc = new Location(world, loc.getBlockX() + x, y, loc.getBlockZ() + z);
                if (Math.abs(x) == radius && Math.abs(z) == radius) {
                    blockLoc.getBlock().setType(Material.GLOWSTONE);
                } else {
                    blockLoc.getBlock().setType(Material.BEDROCK);
                }
            }
        }
    }
}

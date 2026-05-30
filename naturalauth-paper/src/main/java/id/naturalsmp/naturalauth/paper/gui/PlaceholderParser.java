package id.naturalsmp.naturalauth.paper.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class PlaceholderParser {

    /**
     * Parses PlaceholderAPI and ItemsAdder placeholders in the given text.
     * Uses reflection to avoid compile-time dependencies.
     */
    public static String parse(Player player, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 1. Parse PlaceholderAPI placeholders if available
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable t) {
                // Ignore any issues during PlaceholderAPI parsing
            }
        }

        // 2. Parse ItemsAdder emojis (e.g. :intro:) if available
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            try {
                Class<?> clazz = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
                java.lang.reflect.Method method = clazz.getMethod("replaceFontImages", String.class);
                text = (String) method.invoke(null, text);
            } catch (Throwable t) {
                // Ignore any issues during ItemsAdder parsing
            }
        }

        // 3. Translate classic color codes
        text = ChatColor.translateAlternateColorCodes('&', text);

        return text;
    }
}

package id.naturalsmp.naturalauth.velocity;

import java.util.UUID;

/**
 * Safe wrapper around Floodgate API.
 * All calls are guarded so the plugin works even without Floodgate installed.
 */
public final class FloodgateHelper {

    private static final boolean AVAILABLE;

    static {
        boolean found = false;
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            // Also verify an instance actually exists (may be loaded but not initialized)
            org.geysermc.floodgate.api.FloodgateApi.getInstance();
            found = true;
        } catch (Exception ignored) {
            // Floodgate not installed — this is fine
        }
        AVAILABLE = found;
    }

    private FloodgateHelper() {}

    /** Returns true if Floodgate is installed and initialized on this proxy. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Returns true if the player is a Bedrock player connected via Floodgate.
     * Always returns false if Floodgate is not available.
     */
    public static boolean isFloodgatePlayer(UUID uuid) {
        if (!AVAILABLE) return false;
        try {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        } catch (Exception e) {
            return false;
        }
    }
}

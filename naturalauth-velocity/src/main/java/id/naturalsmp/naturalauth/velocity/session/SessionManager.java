package id.naturalsmp.naturalauth.velocity.session;

import id.naturalsmp.naturalauth.velocity.database.DatabaseManager;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final DatabaseManager databaseManager;
    private final int expiryHours;
    private final boolean enabled;
    
    private final Map<UUID, String> activeTokens = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionManager(DatabaseManager databaseManager, int expiryHours, boolean enabled) {
        this.databaseManager = databaseManager;
        this.expiryHours = expiryHours;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String createSession(UUID uuid, String ip) {
        if (!enabled) return null;

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        activeTokens.put(uuid, token);
        databaseManager.saveSession(uuid, ip, token, expiryHours);
        return token;
    }

    public boolean checkAutoLogin(UUID uuid, String ip) {
        if (!enabled) return false;

        // Try memory cache first
        String cachedToken = activeTokens.get(uuid);
        if (cachedToken != null) {
            return databaseManager.validateSession(uuid, ip, cachedToken);
        }

        // Try DB lookup
        String dbToken = databaseManager.getSessionToken(uuid, ip);
        if (dbToken != null) {
            activeTokens.put(uuid, dbToken);
            return true;
        }

        return false;
    }

    public void removeSession(UUID uuid) {
        activeTokens.remove(uuid);
        databaseManager.clearSession(uuid);
    }
}

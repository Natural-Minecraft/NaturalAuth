package id.naturalsmp.naturalauth.velocity.command;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

public class CrackedCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public CrackedCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2 || !args[0].equalsIgnoreCase("confirm")) {
            // Send cracked warning UI
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l┌────────────────────────────────────────┐"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l│              [!] PERINGATAN [!]              │"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l└────────────────────────────────────────┘"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7Dengan mengaktifkan status Cracked, server tidak akan"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7memaksa verifikasi online Mojang untuk akun Anda."));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cAnda WAJIB menyetel password baru untuk masuk nanti!"));
            player.sendMessage(Component.text(""));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§eKetik §a/cracked confirm <password_baru> §euntuk mengaktifkan."));
            return;
        }

        String password = args[1];
        if (plugin.getVelocityListener() != null) {
            String strengthError = plugin.getVelocityListener().validatePasswordStrength(player.getUsername(), password, false);
            if (strengthError != null) {
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(strengthError));
                return;
            }
        } else {
            if (password == null || password.length() < 6) {
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cPassword minimal harus 6 karakter!"));
                return;
            }
        }

        // Hash password using BCrypt
        int rounds = 10;
        if (plugin.getConfig() != null && plugin.getConfig().getTable("settings") != null) {
            Long r = plugin.getConfig().getTable("settings").getLong("bcrypt-rounds");
            if (r != null) rounds = r.intValue();
        }
        String passwordHash = org.mindrot.jbcrypt.BCrypt.hashpw(password, org.mindrot.jbcrypt.BCrypt.gensalt(rounds));

        // Update password and set player status as Cracked in DB
        plugin.getDatabaseManager().updatePassword(player.getUniqueId(), passwordHash);
        plugin.getDatabaseManager().setPremium(player.getUniqueId(), false);
        plugin.logActivity(player.getUniqueId(), player.getUsername(), "PREMIUM_OFF", player.getRemoteAddress().getAddress().getHostAddress(), "Menonaktifkan mode Premium Mojang (Cracked)");

        // Fetch success message from config
        String msg = "§aFitur Cracked diaktifkan! Silakan masuk kembali dan gunakan password Anda.";
        if (plugin.getConfig() != null && plugin.getConfig().getTable("messages") != null) {
            String configMsg = plugin.getConfig().getTable("messages").getString("cracked-enabled");
            if (configMsg != null) {
                msg = configMsg.replace("&", "§");
            }
        }

        // Kick player to enforce the change
        player.disconnect(Component.text(msg));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
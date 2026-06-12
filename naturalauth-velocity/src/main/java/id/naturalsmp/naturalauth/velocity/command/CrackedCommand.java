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
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
            // Send cracked warning UI
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l┌────────────────────────────────────────┐"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l│              [!] PERINGATAN [!]              │"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l└────────────────────────────────────────┘"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7Dengan mengaktifkan status Cracked, server tidak akan"));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7memaksa verifikasi online Mojang untuk akun Anda."));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§eAnda akan diminta memasukkan password saat join kembali."));
            player.sendMessage(Component.text(""));
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§eKetik §a/cracked confirm §euntuk mengaktifkan."));
            return;
        }

        // Set player status as Cracked in DB
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
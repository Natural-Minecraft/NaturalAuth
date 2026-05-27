package id.naturalsmp.naturalauth.velocity.command;

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
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
            // Send cracked warning UI
            player.sendMessage(Component.text("§e§l┌────────────────────────────────────────┐"));
            player.sendMessage(Component.text("§e§l│              [!] PERINGATAN [!]              │"));
            player.sendMessage(Component.text("§e§l└────────────────────────────────────────┘"));
            player.sendMessage(Component.text("§7Dengan mengaktifkan status Cracked, server tidak akan"));
            player.sendMessage(Component.text("§7memaksa verifikasi online Mojang untuk akun Anda."));
            player.sendMessage(Component.text("§eAnda akan diminta memasukkan password saat join kembali."));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§eKetik §a/cracked confirm §euntuk mengaktifkan."));
            return;
        }

        // Set player status as Cracked in DB
        plugin.getDatabaseManager().setPremium(player.getUniqueId(), false);

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

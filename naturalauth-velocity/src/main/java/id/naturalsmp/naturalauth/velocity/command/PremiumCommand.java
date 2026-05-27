package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

public class PremiumCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public PremiumCommand(NaturalAuthVelocity plugin) {
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
            // Send premium warning UI
            player.sendMessage(Component.text("§c§l┌────────────────────────────────────────┐"));
            player.sendMessage(Component.text("§c§l│            [!] PERINGATAN KERAS [!]            │"));
            player.sendMessage(Component.text("§c§l└────────────────────────────────────────┘"));
            player.sendMessage(Component.text("§7Dengan mengaktifkan status Premium, server akan memaksa"));
            player.sendMessage(Component.text("§7autentikasi Mojang Online-Mode untuk akun Anda."));
            player.sendMessage(Component.text("§ePastikan Anda login menggunakan launcher Original!"));
            player.sendMessage(Component.text("§cJika tidak, Anda akan terkunci & tidak bisa join lagi."));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§eKetik §a/premium confirm §euntuk mengaktifkan."));
            return;
        }

        // Set player status as Premium in DB
        plugin.getDatabaseManager().setPremium(player.getUniqueId(), true);

        // Fetch success message from config
        String msg = "§aFitur Premium diaktifkan! Silakan join kembali menggunakan launcher original Anda secara aman.";
        if (plugin.getConfig() != null && plugin.getConfig().getTable("messages") != null) {
            String configMsg = plugin.getConfig().getTable("messages").getString("premium-enabled");
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

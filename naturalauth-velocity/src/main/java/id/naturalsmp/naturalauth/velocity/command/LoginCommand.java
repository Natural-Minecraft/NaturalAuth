package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

import java.util.concurrent.CompletableFuture;

public class LoginCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public LoginCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        if (plugin.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("§a§lNaturalAuth §r§eAnda sudah login! Menyinkronkan status login ke server..."));
            if (plugin.getVelocityListener() != null) {
                plugin.getVelocityListener().finalizeAuth(player);
            }
            return;
        }

        String[] args = invocation.arguments();

        // No args → open GUI as a helpful fallback
        if (args.length < 1) {
            // Asynchronously check database to prevent proxy lag spike
            CompletableFuture.supplyAsync(() -> plugin.getDatabaseManager().isRegistered(player.getUsername()))
                .thenAcceptAsync(registered -> {
                    if (!registered) {
                        player.sendMessage(Component.text("§cAkun Anda belum terdaftar! Gunakan §e/register <password> <password>§c untuk mendaftar."));
                        return;
                    }
                    if (plugin.getVelocityListener() != null) {
                        plugin.getVelocityListener().startAuthFlow(player);
                    } else {
                        player.sendMessage(Component.text("§cGunakan: §e/login <password>"));
                    }
                });
            return;
        }

        String password = args[0];

        // Asynchronously check registration status and verify password/handle login off the main thread
        CompletableFuture.supplyAsync(() -> plugin.getDatabaseManager().isRegistered(player.getUsername()))
            .thenAcceptAsync(registered -> {
                if (!registered) {
                    player.sendMessage(Component.text("§cAkun Anda belum terdaftar! Gunakan §e/register <password> <password>§c untuk mendaftar."));
                    return;
                }

                if (plugin.getVelocityListener() != null) {
                    plugin.getVelocityListener().handleTextLogin(player, password);
                } else {
                    // Fallback
                    CompletableFuture.supplyAsync(() -> plugin.verifyPassword(player.getUsername(), password))
                        .thenAcceptAsync(correct -> {
                            if (correct) {
                                plugin.setAuthenticated(player.getUniqueId(), true);
                                player.sendMessage(Component.text("§aLogin berhasil!"));
                            } else {
                                player.sendMessage(Component.text("§cPassword salah!"));
                            }
                        });
                }
            });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}

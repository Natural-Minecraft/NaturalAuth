package id.naturalsmp.naturalauth.velocity.command;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class ForgotPasswordCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public ForgotPasswordCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        String baseUrl = "https://naturalsmp.net";
        if (plugin.getConfig() != null && plugin.getConfig().getTable("settings") != null) {
            String configUrl = plugin.getConfig().getTable("settings").getString("website-url");
            if (configUrl != null) baseUrl = configUrl;
        }

        String encodedUsername;
        try {
            encodedUsername = java.net.URLEncoder.encode(player.getUsername(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            encodedUsername = player.getUsername();
        }
        String url = baseUrl + "/forgot-password?username=" + encodedUsername;

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§8§l========================================="));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l🔑 Lupa Kata Sandi / Forgot Password"));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§8§l========================================="));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§fKamu bisa mereset kata sandi secara mandiri dan aman."));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§fSilakan klik tautan di bawah ini untuk membuka website:"));
        player.sendMessage(Component.text(""));

        // Clickable URL component using Kyori Adventure
        Component linkComponent = Component.text("👉 [ KLIK DI SINI UNTUK RESET PASSWORD ] 👈")
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url));

        player.sendMessage(linkComponent);
        player.sendMessage(Component.text(""));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7Tautan ini akan mengarahkanmu ke portal reset password resmi."));
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§8§l========================================="));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
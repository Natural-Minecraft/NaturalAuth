package id.naturalsmp.naturalauth.velocity;

import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.listener.VelocityListener;
import net.kyori.adventure.text.Component;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class BedrockFormHelper implements BedrockAuthProvider {

    private final NaturalAuthVelocity plugin;
    private final VelocityListener listener;

    public BedrockFormHelper(NaturalAuthVelocity plugin, VelocityListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public void openAuthForm(Player player, boolean registered) {
        UUID uuid = player.getUniqueId();
        
        if (!registered) {
            CustomForm form = CustomForm.builder()
                    .title("Create Account \u26A0")
                    .input("Welcome! Create a secure password below.\n\n§7Your password protects your progress.\n\n§fPassword:", "Min. 4 characters...")
                    .input("Confirm Password:", "Repeat password...")
                    .validResultHandler(response -> {
                        String password = response.getInput(0);
                        String confirm = response.getInput(1);
                        
                        if (password == null || password.isEmpty() || confirm == null || confirm.isEmpty()) {
                            openBedrockErrorForm(player, "Registrasi Gagal", "Password tidak boleh kosong!", () -> openAuthForm(player, false));
                            return;
                        }
                        
                        if (password.length() < 4) {
                            openBedrockErrorForm(player, "Registrasi Gagal", "Password minimal 4 karakter!", () -> openAuthForm(player, false));
                            return;
                        }
                        
                        if (!password.equals(confirm)) {
                            openBedrockErrorForm(player, "Registrasi Gagal", "Konfirmasi password tidak cocok!", () -> openAuthForm(player, false));
                            return;
                        }
                        
                        boolean success = plugin.register(uuid, player.getUsername(), password);
                        if (success) {
                            player.sendMessage(Component.text("§aRegistrasi berhasil!"));
                            listener.handlePasswordVerified(player);
                        } else {
                            openBedrockErrorForm(player, "Registrasi Gagal", "Registrasi gagal! Silakan coba lagi.", () -> openAuthForm(player, false));
                        }
                    })
                    .closedResultHandler(() -> reopenAuthFormDelayed(player, false))
                    .build();

            sendForm(uuid, form);
        } else {
            CustomForm form = CustomForm.builder()
                    .title("Welcome Back! \u26A0")
                    .input("Please enter your password to continue.\n\n§7If you forgot your password, toggle below.\n\n§fPassword:", "Enter your password...")
                    .toggle("§eSaya Lupa Password (Buka Link Reset)", false)
                    .validResultHandler(response -> {
                        boolean forgot = response.getToggle(1);
                        if (forgot) {
                            player.sendMessage(Component.text("§8§m──────────────────────────────────"));
                            player.sendMessage(Component.text("§e§lNaturalSMP §r§eSilakan klik link di bawah untuk memulihkan password Anda:"));
                            player.sendMessage(Component.text("§b§nhttps://naturalsmp.net/support/help/lupa-password")
                                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://naturalsmp.net/support/help/lupa-password"))
                                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("§7Klik untuk membuka website"))));
                            player.sendMessage(Component.text("§8§m──────────────────────────────────"));
                            
                            reopenAuthFormDelayed(player, true);
                            return;
                        }

                        String password = response.getInput(0);
                        if (password == null || password.isEmpty()) {
                            openBedrockErrorForm(player, "Login Gagal", "Password tidak boleh kosong!", () -> openAuthForm(player, true));
                            return;
                        }
                        
                        if (plugin.verifyPassword(player.getUsername(), password)) {
                            player.sendMessage(Component.text("§aLogin berhasil!"));
                            listener.handlePasswordVerified(player);
                        } else {
                            openBedrockErrorForm(player, "Login Gagal", "Password salah!", () -> openAuthForm(player, true));
                        }
                    })
                    .closedResultHandler(() -> reopenAuthFormDelayed(player, true))
                    .build();

            sendForm(uuid, form);
        }
    }

    private void openBedrockErrorForm(Player player, String title, String errorMsg, Runnable onClose) {
        org.geysermc.cumulus.form.SimpleForm form = org.geysermc.cumulus.form.SimpleForm.builder()
                .title(title)
                .content("§c§l✖ GERBANG AUTENTIKASI ✖\n\n§7Terjadi kesalahan:\n§e" + errorMsg + "\n\n§7Silakan tekan tombol di bawah untuk mencoba kembali.")
                .button("Coba Lagi")
                .validResultHandler(response -> {
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        if (player.isActive()) {
                            onClose.run();
                        }
                    }).delay(500, TimeUnit.MILLISECONDS).schedule();
                })
                .closedResultHandler(() -> {
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        if (player.isActive()) {
                            onClose.run();
                        }
                    }).delay(500, TimeUnit.MILLISECONDS).schedule();
                })
                .build();
        sendForm(player.getUniqueId(), form);
    }

    @Override
    public void openRulesForm(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Build the rules text
        StringBuilder rulesContent = new StringBuilder("§e" + plugin.getRulesContent() + "\n\n");
        for (String rule : plugin.getRulesList()) {
            rulesContent.append("§f").append(rule).append("\n");
        }
        rulesContent.append("\n§7").append(plugin.getRulesToggleLabel()).append(".");
        
        CustomForm form = CustomForm.builder()
                .title(plugin.getRulesTitle())
                .label(rulesContent.toString())
                .toggle(plugin.getRulesToggleLabel(), false)
                .validResultHandler(response -> {
                    boolean accepted = response.getToggle(1);
                    if (!accepted) {
                        player.sendMessage(Component.text("§cAnda harus menyetujui peraturan untuk bermain!"));
                        reopenRulesFormDelayed(player);
                        return;
                    }
                    
                    plugin.getDatabaseManager().setRulesAccepted(uuid);
                    player.sendMessage(Component.text("§aAnda telah menyetujui peraturan server!"));
                    listener.finalizeAuth(player);
                })
                .closedResultHandler(() -> reopenRulesFormDelayed(player))
                .build();

        sendForm(uuid, form);
    }

    @Override
    public void reopenAuthFormDelayed(Player player, boolean registered) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (player.isActive() && !plugin.isAuthenticated(player.getUniqueId())) {
                openAuthForm(player, registered);
            }
        }).delay(1, TimeUnit.SECONDS).schedule();
    }

    @Override
    public void reopenRulesFormDelayed(Player player) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (player.isActive() && plugin.isPendingRules(player.getUniqueId())) {
                openRulesForm(player);
            }
        }).delay(1, TimeUnit.SECONDS).schedule();
    }

    private void sendForm(UUID uuid, org.geysermc.cumulus.form.Form form) {
        try {
            FloodgateApi.getInstance().sendForm(uuid, form);
        } catch (Exception ignored) {}
    }
}

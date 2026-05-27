package id.naturalsmp.naturalauth.paper.gui;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.NaturalAuthPaper;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public class DialogRenderer {

    public static boolean isDialogApiAvailable() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void openDialogGUI(NaturalAuthPaper plugin, Player player, String type, String prompt) {
        if (type.equalsIgnoreCase("REGISTER")) {
            openRegisterDialog(plugin, player, prompt);
        } else {
            openLoginDialog(plugin, player, prompt);
        }
    }

    public static void openLoginDialog(NaturalAuthPaper plugin, Player player, String prompt) {
        TextDialogInput passwordInput = DialogInput.text(
                "password",
                200,
                Component.text("Password"),
                true,
                "",
                72,
                null
        );

        ActionButton signInButton = ActionButton.builder(Component.text("Masuk"))
                .action(DialogAction.customClick((response, audience) -> {
                    String password = response.getText("password");
                    if (password == null || password.trim().isEmpty()) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, "Login Gagal", "Password tidak boleh kosong!", () -> {
                            openLoginDialog(plugin, player, prompt);
                        });
                        return;
                    }

                    audience.closeDialog();
                    submitPasswordToVelocity(plugin, player, password);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton forgotPasswordButton = ActionButton.builder(Component.text("Lupa Password"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.sendMessage(Component.text("§8§m──────────────────────────────────"));
                    player.sendMessage(Component.text("§e§lNaturalSMP §r§eSilakan klik link di bawah untuk memulihkan password Anda:"));
                    player.sendMessage(Component.text("§b§nhttps://naturalsmp.net/support/help/lupa-password")
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://naturalsmp.net/support/help/lupa-password"))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("§7Klik untuk membuka website"))));
                    player.sendMessage(Component.text("§8§m──────────────────────────────────"));

                    // Reopen the login dialog in case they want to try logging in again
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            openLoginDialog(plugin, player, prompt);
                        }
                    }, 100L); // wait 5 seconds so they have time to click the link
                }, ClickCallback.Options.builder().build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Login"))
                        .canCloseWithEscape(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("§e" + prompt))
                        ))
                        .inputs(List.of(passwordInput))
                        .build())
                .type(DialogType.confirmation(signInButton, forgotPasswordButton))
        );

        player.showDialog(dialog);
    }

    public static void openRegisterDialog(NaturalAuthPaper plugin, Player player, String prompt) {
        TextDialogInput passwordInput = DialogInput.text(
                "password",
                200,
                Component.text("Password Baru"),
                true,
                "",
                72,
                null
        );

        TextDialogInput confirmInput = DialogInput.text(
                "confirm",
                200,
                Component.text("Konfirmasi Password"),
                true,
                "",
                72,
                null
        );

        ActionButton registerButton = ActionButton.builder(Component.text("Daftar"))
                .action(DialogAction.customClick((response, audience) -> {
                    String password = response.getText("password");
                    String confirm = response.getText("confirm");

                    if (password == null || password.trim().isEmpty() || password.length() < 4) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, "Registrasi Gagal", "Password minimal 4 karakter!", () -> {
                            openRegisterDialog(plugin, player, prompt);
                        });
                        return;
                    }

                    if (confirm == null || !confirm.equals(password)) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, "Registrasi Gagal", "Konfirmasi password tidak cocok!", () -> {
                            openRegisterDialog(plugin, player, prompt);
                        });
                        return;
                    }

                    audience.closeDialog();
                    submitPasswordToVelocity(plugin, player, password);
                }, ClickCallback.Options.builder().build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Register"))
                        .canCloseWithEscape(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("§e" + prompt))
                        ))
                        .inputs(List.of(passwordInput, confirmInput))
                        .build())
                .type(DialogType.notice(registerButton))
        );

        player.showDialog(dialog);
    }

    public static void openErrorDialog(NaturalAuthPaper plugin, Player player, String title, String errorMsg, Runnable onClose) {
        ActionButton okButton = ActionButton.builder(Component.text("Coba Lagi"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            onClose.run();
                        }
                    }, 5L);
                }, ClickCallback.Options.builder().build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title))
                        .canCloseWithEscape(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("§c§l✖ GERBANG AUTENTIKASI ✖")),
                                DialogBody.plainMessage(Component.text("")),
                                DialogBody.plainMessage(Component.text("§7Terjadi kesalahan:")),
                                DialogBody.plainMessage(Component.text("§e" + errorMsg)),
                                DialogBody.plainMessage(Component.text("")),
                                DialogBody.plainMessage(Component.text("§7Silakan klik tombol di bawah untuk mengulangi."))
                        ))
                        .build())
                .type(DialogType.notice(okButton))
        );

        player.showDialog(dialog);
    }

    public static void openRulesDialog(NaturalAuthPaper plugin, Player player) {
        BooleanDialogInput agreeCheckbox = DialogInput.bool(
                "agree",
                Component.text("Saya setuju dengan peraturan server"),
                false,
                "true",
                "false"
        );

        ActionButton acceptButton = ActionButton.builder(Component.text("Setuju"))
                .action(DialogAction.customClick((response, audience) -> {
                    Boolean agree = response.getBoolean("agree");
                    if (agree == null || !agree) {
                        player.sendMessage("§c§lNaturalAuth §r§cAnda harus mencentang persetujuan untuk bermain!");
                        audience.closeDialog();
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline()) {
                                openRulesDialog(plugin, player);
                            }
                        }, 5L);
                        return;
                    }

                    audience.closeDialog();
                    plugin.getListener().sendPacketRulesAccepted(player);
                    plugin.setPendingRules(player.getUniqueId(), false);
                    plugin.setAuthenticated(player.getUniqueId(), true);
                    player.sendMessage("§a§lNaturalAuth §r§aTerima kasih telah menyetujui peraturan server!");
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton declineButton = ActionButton.builder(Component.text("Tolak"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    plugin.getListener().sendPacketRulesDeclined(player);
                    plugin.setPendingRules(player.getUniqueId(), false);
                    player.kick(Component.text("§cAnda harus menyetujui peraturan untuk bermain!"));
                }, ClickCallback.Options.builder().build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Peraturan Server"))
                        .canCloseWithEscape(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("§6§lPERATURAN SERVER NATURALSMP")),
                                DialogBody.plainMessage(Component.text("§f1. Dilarang menggunakan cheat, hack, atau exploit.")),
                                DialogBody.plainMessage(Component.text("§f2. Saling menghormati sesama player dan staff.")),
                                DialogBody.plainMessage(Component.text("§f3. Dilarang spamming, promosi, atau toxic.")),
                                DialogBody.plainMessage(Component.text("§f4. Patuhi petunjuk dan keputusan staff.")),
                                DialogBody.plainMessage(Component.text("§f5. Bermainlah dengan jujur dan bersenang-senang!"))
                        ))
                        .inputs(List.of(agreeCheckbox))
                        .build())
                .type(DialogType.confirmation(acceptButton, declineButton))
        );

        player.showDialog(dialog);
    }

    private static void submitPasswordToVelocity(NaturalAuthPaper plugin, Player player, String password) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_PASSWORD);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(password);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_PASSWORD to Velocity!");
            e.printStackTrace();
        }
    }

    public static void openEmailLinkDialog(NaturalAuthPaper plugin, Player player) {
        TextDialogInput emailInput = DialogInput.text(
                "email",
                200,
                Component.text("Alamat Email Anda"),
                true,
                "",
                72,
                null
        );

        ActionButton submitButton = ActionButton.builder(Component.text("Kaitkan"))
                .action(DialogAction.customClick((response, audience) -> {
                    String email = response.getText("email");
                    if (email == null || email.trim().isEmpty() || !email.contains("@")) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, "Kaitkan Email", "Format email tidak valid!", () -> {
                            openEmailLinkDialog(plugin, player);
                        });
                        return;
                    }

                    audience.closeDialog();
                    submitEmailToVelocity(plugin, player, email);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton skipButton = ActionButton.builder(Component.text("Lewati"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    submitEmailToVelocity(plugin, player, ""); // Send empty email to complete registration
                }, ClickCallback.Options.builder().build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Kaitkan Email"))
                        .canCloseWithEscape(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text("§a§l✔ REGISTRASI BERHASIL!")),
                                DialogBody.plainMessage(Component.text("")),
                                DialogBody.plainMessage(Component.text("§eMengaitkan email sangat direkomendasikan agar:")),
                                DialogBody.plainMessage(Component.text("§f1. Bisa reset password mandiri via web jika lupa.")),
                                DialogBody.plainMessage(Component.text("§f2. Bisa ganti email/password tanpa hubungi Admin.")),
                                DialogBody.plainMessage(Component.text("")),
                                DialogBody.plainMessage(Component.text("§7Masukkan email Anda di bawah untuk mengaitkan:"))
                        ))
                        .inputs(List.of(emailInput))
                        .build())
                .type(DialogType.confirmation(submitButton, skipButton))
        );

        player.showDialog(dialog);
    }

    private static void submitEmailToVelocity(NaturalAuthPaper plugin, Player player, String email) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_EMAIL);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(email);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_EMAIL to Velocity!");
            e.printStackTrace();
        }
    }
}

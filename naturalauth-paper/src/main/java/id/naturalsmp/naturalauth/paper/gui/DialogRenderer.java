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
import java.util.ArrayList;
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

    /**
     * Returns a DialogBody containing the LogoRPUnicode from config,
     * or null if the config value is empty/not set.
     */
    private static DialogBody getLogoBody(NaturalAuthPaper plugin, Player player) {
        String logo = plugin.getConfig().getString("LogoRPUnicode", "");
        if (logo == null || logo.isEmpty()) return null;
        logo = PlaceholderParser.parse(player, logo);
        return DialogBody.plainMessage(Component.text(logo));
    }

    public static void openDialogGUI(NaturalAuthPaper plugin, Player player, String type, String prompt, String language) {
        if (type.equalsIgnoreCase("REGISTER")) {
            openRegisterDialog(plugin, player, prompt, language);
        } else if (type.equalsIgnoreCase("PRE_REG_LANG")) {
            openPreRegLangDialog(plugin, player, language);
        } else if (type.equalsIgnoreCase("PRE_REG_TYPE")) {
            openPreRegTypeDialog(plugin, player, language);
        } else if (type.equalsIgnoreCase("PRE_REG_PREMIUM")) {
            openPreRegPremiumDialog(plugin, player, prompt, language);
        } else {
            openLoginDialog(plugin, player, prompt, language);
        }
    }

    public static void openLoginDialog(NaturalAuthPaper plugin, Player player, String prompt, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        TextDialogInput passwordInput = DialogInput.text(
                "password",
                200,
                Component.text("Password"),
                true,
                "",
                72,
                null
        );

        ActionButton signInButton = ActionButton.builder(Component.text(isEnglish ? "Log In" : "Masuk"))
                .action(DialogAction.customClick((response, audience) -> {
                    String password = response.getText("password");
                    if (password == null || password.trim().isEmpty()) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, 
                                isEnglish ? "Login Failed" : "Login Gagal", 
                                isEnglish ? "Password cannot be empty!" : "Password tidak boleh kosong!", 
                                language, 
                                () -> {
                                    openLoginDialog(plugin, player, prompt, language);
                                });
                        return;
                    }

                    audience.closeDialog();
                    submitPasswordToVelocity(plugin, player, password);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton forgotPasswordButton = ActionButton.builder(Component.text(isEnglish ? "Forgot Password" : "Lupa Password"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.sendMessage(Component.text("§8§m──────────────────────────────────"));
                    player.sendMessage(Component.text(isEnglish 
                            ? "§e§lNaturalSMP §r§ePlease click the link below to recover your password:"
                            : "§e§lNaturalSMP §r§eSilakan klik link di bawah untuk memulihkan password Anda:"));
                    player.sendMessage(Component.text("§b§nhttps://naturalsmp.net/support/help/lupa-password")
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://naturalsmp.net/support/help/lupa-password"))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(isEnglish ? "§7Click to open website" : "§7Klik untuk membuka website"))));
                    player.sendMessage(Component.text("§8§m──────────────────────────────────"));

                    // Reopen the login dialog in case they want to try logging in again
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            openLoginDialog(plugin, player, prompt, language);
                        }
                    }, 100L); // wait 5 seconds so they have time to click the link
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton quitButton = ActionButton.builder(Component.text(isEnglish ? "Quit" : "Quit / Keluar"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.kick(Component.text(isEnglish ? "§cYou chose to quit." : "§cAnda memilih untuk keluar (Quit)."));
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> loginBody = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            loginBody.add(DialogBody.plainMessage(Component.text("")));
            loginBody.add(DialogBody.plainMessage(Component.text("")));
            loginBody.add(logoBody);
            loginBody.add(DialogBody.plainMessage(Component.text("")));
        }
        loginBody.add(DialogBody.plainMessage(Component.text("§e" + PlaceholderParser.parse(player, prompt))));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Login"))
                        .canCloseWithEscape(false)
                        .body(loginBody)
                        .inputs(List.of(passwordInput))
                        .build())
                .type(DialogType.multiAction(List.of(signInButton, forgotPasswordButton), quitButton, 2))
        );

        player.showDialog(dialog);
    }

    public static void openRegisterDialog(NaturalAuthPaper plugin, Player player, String prompt, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        TextDialogInput passwordInput = DialogInput.text(
                "password",
                200,
                Component.text(isEnglish ? "New Password" : "Password Baru"),
                true,
                "",
                72,
                null
        );

        TextDialogInput confirmInput = DialogInput.text(
                "confirm",
                200,
                Component.text(isEnglish ? "Confirm Password" : "Konfirmasi Password"),
                true,
                "",
                72,
                null
        );

        ActionButton registerButton = ActionButton.builder(Component.text(isEnglish ? "Register" : "Daftar"))
                .action(DialogAction.customClick((response, audience) -> {
                    String password = response.getText("password");
                    String confirm = response.getText("confirm");

                    if (password == null || password.trim().isEmpty() || password.length() < 4) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, 
                                isEnglish ? "Registration Failed" : "Registrasi Gagal", 
                                isEnglish ? "Password must be at least 4 characters!" : "Password minimal 4 karakter!", 
                                language, 
                                () -> {
                                    openRegisterDialog(plugin, player, prompt, language);
                                });
                        return;
                    }

                    if (confirm == null || !confirm.equals(password)) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, 
                                isEnglish ? "Registration Failed" : "Registrasi Gagal", 
                                isEnglish ? "Passwords do not match!" : "Konfirmasi password tidak cocok!", 
                                language, 
                                () -> {
                                    openRegisterDialog(plugin, player, prompt, language);
                                });
                        return;
                    }

                    audience.closeDialog();
                    submitPasswordToVelocity(plugin, player, password);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton langButton = ActionButton.builder(Component.text(isEnglish ? "Language: English" : "Language: Bahasa Indonesia"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    submitLanguageToVelocity(plugin, player, isEnglish ? "indonesia" : "english");
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton quitButton = ActionButton.builder(Component.text(isEnglish ? "Quit" : "Quit / Keluar"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.kick(Component.text(isEnglish ? "§cYou chose to quit." : "§cAnda memilih untuk keluar (Quit)."));
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> registerBody = new ArrayList<>();
        DialogBody logoBodyReg = getLogoBody(plugin, player);
        if (logoBodyReg != null) {
            registerBody.add(DialogBody.plainMessage(Component.text("")));
            registerBody.add(DialogBody.plainMessage(Component.text("")));
            registerBody.add(logoBodyReg);
            registerBody.add(DialogBody.plainMessage(Component.text("")));
        }
        registerBody.add(DialogBody.plainMessage(Component.text("§7Ganti Bahasa / Change Language")));
        registerBody.add(DialogBody.plainMessage(Component.text("")));
        registerBody.add(DialogBody.plainMessage(Component.text("§e" + PlaceholderParser.parse(player, prompt))));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Register"))
                        .canCloseWithEscape(false)
                        .body(registerBody)
                        .inputs(List.of(passwordInput, confirmInput))
                        .build())
                .type(DialogType.multiAction(List.of(registerButton, langButton), quitButton, 2))
        );

        player.showDialog(dialog);
    }

    public static void openErrorDialog(NaturalAuthPaper plugin, Player player, String title, String errorMsg, String language, Runnable onClose) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        ActionButton okButton = ActionButton.builder(Component.text(isEnglish ? "Try Again" : "Coba Lagi"))
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
                                DialogBody.plainMessage(Component.text(isEnglish ? "§c§l✖ AUTHENTICATION GATE ✖" : "§c§l✖ GERBANG AUTENTIKASI ✖")),
                                DialogBody.plainMessage(Component.text("")),
                                DialogBody.plainMessage(Component.text(isEnglish ? "§7An error occurred:" : "§7Terjadi kesalahan:")),
                                DialogBody.plainMessage(Component.text("§e" + errorMsg)),
                                DialogBody.plainMessage(Component.text("")),
                                DialogBody.plainMessage(Component.text(isEnglish ? "§7Please click the button below to retry." : "§7Silakan klik tombol di bawah untuk mengulangi."))
                        ))
                        .build())
                .type(DialogType.notice(okButton))
        );

        player.showDialog(dialog);
    }

    public static void openRulesDialog(NaturalAuthPaper plugin, Player player, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        BooleanDialogInput agreeCheckbox = DialogInput.bool(
                "agree",
                Component.text(isEnglish ? "I agree to the server rules" : "Saya setuju dengan peraturan server"),
                false,
                "true",
                "false"
        );

        ActionButton acceptButton = ActionButton.builder(Component.text(isEnglish ? "Accept" : "Setuju"))
                .action(DialogAction.customClick((response, audience) -> {
                    Boolean agree = response.getBoolean("agree");
                    if (agree == null || !agree) {
                        player.sendMessage(isEnglish 
                                ? "§c§lNaturalAuth §r§cYou must check the agreement to play!" 
                                : "§c§lNaturalAuth §r§cAnda harus mencentang persetujuan untuk bermain!");
                        audience.closeDialog();
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline()) {
                                openRulesDialog(plugin, player, language);
                            }
                        }, 5L);
                        return;
                    }

                    audience.closeDialog();
                    plugin.getListener().sendPacketRulesAccepted(player);
                    plugin.setPendingRules(player.getUniqueId(), false);
                    plugin.setAuthenticated(player.getUniqueId(), true);
                    player.sendMessage(isEnglish 
                            ? "§a§lNaturalAuth §r§aThank you for accepting the server rules!"
                            : "§a§lNaturalAuth §r§aTerima kasih telah menyetujui peraturan server!");
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton declineButton = ActionButton.builder(Component.text(isEnglish ? "Decline" : "Tolak"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    plugin.getListener().sendPacketRulesDeclined(player);
                    plugin.setPendingRules(player.getUniqueId(), false);
                    player.kick(Component.text(isEnglish ? "§cYou must agree to the rules to play!" : "§cAnda harus menyetujui peraturan untuk bermain!"));
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> bodyList = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(logoBody);
            bodyList.add(DialogBody.plainMessage(Component.text("")));
        }

        if (isEnglish) {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§6§lNATURALSMP SERVER RULES")),
                    DialogBody.plainMessage(Component.text("§f1. No cheating, hacking, or exploits.")),
                    DialogBody.plainMessage(Component.text("§f2. Respect other players and staff.")),
                    DialogBody.plainMessage(Component.text("§f3. No spamming, advertising, or toxic behavior.")),
                    DialogBody.plainMessage(Component.text("§f4. Obey staff instructions and decisions.")),
                    DialogBody.plainMessage(Component.text("§f5. Play fair and have fun!"))
            ));
        } else {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§6§lPERATURAN SERVER NATURALSMP")),
                    DialogBody.plainMessage(Component.text("§f1. Dilarang menggunakan cheat, hack, atau exploit.")),
                    DialogBody.plainMessage(Component.text("§f2. Saling menghormati sesama player dan staff.")),
                    DialogBody.plainMessage(Component.text("§f3. Dilarang spamming, promosi, atau toxic.")),
                    DialogBody.plainMessage(Component.text("§f4. Patuhi petunjuk dan keputusan staff.")),
                    DialogBody.plainMessage(Component.text("§f5. Bermainlah dengan jujur dan bersenang-senang!"))
            ));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(isEnglish ? "Server Rules" : "Peraturan Server"))
                        .canCloseWithEscape(false)
                        .body(bodyList)
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

    private static void submitLanguageToVelocity(NaturalAuthPaper plugin, Player player, String language) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_LANGUAGE);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(language);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_LANGUAGE to Velocity!");
            e.printStackTrace();
        }
    }

    public static void openEmailLinkDialog(NaturalAuthPaper plugin, Player player, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        TextDialogInput emailInput = DialogInput.text(
                "email",
                200,
                Component.text(isEnglish ? "Your Email Address" : "Alamat Email Anda"),
                true,
                "",
                72,
                null
        );

        ActionButton submitButton = ActionButton.builder(Component.text(isEnglish ? "Link" : "Kaitkan"))
                .action(DialogAction.customClick((response, audience) -> {
                    String email = response.getText("email");
                    if (email == null || email.trim().isEmpty() || !email.contains("@")) {
                        audience.closeDialog();
                        openErrorDialog(plugin, player, 
                                isEnglish ? "Link Email" : "Kaitkan Email", 
                                isEnglish ? "Invalid email format!" : "Format email tidak valid!", 
                                language, 
                                () -> {
                                    openEmailLinkDialog(plugin, player, language);
                                });
                        return;
                    }

                    audience.closeDialog();
                    submitEmailToVelocity(plugin, player, email);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton skipButton = ActionButton.builder(Component.text(isEnglish ? "Skip" : "Lewati"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    submitEmailToVelocity(plugin, player, ""); // Send empty email to complete registration
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> bodyList = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(logoBody);
            bodyList.add(DialogBody.plainMessage(Component.text("")));
        }

        if (isEnglish) {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§a§l✔ REGISTRATION SUCCESSFUL!")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§eLinking your email is highly recommended so you can:")),
                    DialogBody.plainMessage(Component.text("§f1. Reset your password via website if forgotten.")),
                    DialogBody.plainMessage(Component.text("§f2. Change email/password without contacting Admin.")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§7Enter your email below to link:"))
            ));
        } else {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§a§l✔ REGISTRASI BERHASIL!")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§eMengaitkan email sangat direkomendasikan agar:")),
                    DialogBody.plainMessage(Component.text("§f1. Bisa reset password mandiri via web jika lupa.")),
                    DialogBody.plainMessage(Component.text("§f2. Bisa ganti email/password tanpa hubungi Admin.")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§7Masukkan email Anda di bawah untuk mengaitkan:"))
            ));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(isEnglish ? "Link Email" : "Kaitkan Email"))
                        .canCloseWithEscape(false)
                        .body(bodyList)
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

    public static void openPremiumDialog(NaturalAuthPaper plugin, Player player, String captcha, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        TextDialogInput captchaInput = DialogInput.text(
                "captcha_input",
                200,
                Component.text((isEnglish ? "Type Captcha: " : "Ketik Captcha: ") + captcha),
                true,
                "",
                72,
                null
        );

        ActionButton imPremiumButton = ActionButton.builder(Component.text("I'm Premium"))
                .action(DialogAction.customClick((response, audience) -> {
                    String typed = response.getText("captcha_input");
                    if (typed == null || !typed.equals(captcha)) {
                        audience.closeDialog();
                        player.sendMessage(isEnglish ? "§c§l[!] §r§cIncorrect captcha or does not match!" : "§c§l[!] §r§cCaptcha salah atau tidak cocok!");
                        return;
                    }

                    audience.closeDialog();
                    submitPremiumConfirmToVelocity(plugin, player);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton backButton = ActionButton.builder(Component.text(isEnglish ? "Back" : "Kembali"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.sendMessage(isEnglish ? "§e§l[!] §r§ePremium registration cancelled." : "§e§l[!] §r§ePendaftaran premium dibatalkan.");
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> bodyList = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(logoBody);
            bodyList.add(DialogBody.plainMessage(Component.text("")));
        }
        bodyList.addAll(List.of(
                DialogBody.plainMessage(Component.text(isEnglish ? "§c§lAre you sure your account is premium?" : "§c§lApakah kamu yakin akun mu premium?")),
                DialogBody.plainMessage(Component.text("")),
                DialogBody.plainMessage(Component.text((isEnglish ? "§eFill Captcha: §f" : "§eIsi Captcha: §f") + captcha)),
                DialogBody.plainMessage(Component.text(isEnglish ? "§7Type captcha below to confirm." : "§7Ketik captcha di bawah untuk mengonfirmasi."))
        ));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(isEnglish ? "Premium Confirmation" : "Konfirmasi Premium"))
                        .canCloseWithEscape(true)
                        .body(bodyList)
                        .inputs(List.of(captchaInput))
                        .build())
                .type(DialogType.confirmation(imPremiumButton, backButton))
        );

        player.showDialog(dialog);
    }

    private static void submitPremiumConfirmToVelocity(NaturalAuthPaper plugin, Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_PREMIUM_CONFIRM);
            dos.writeUTF(player.getUniqueId().toString());

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_PREMIUM_CONFIRM to Velocity!");
            e.printStackTrace();
        }
    }

    public static void openPreRegLangDialog(NaturalAuthPaper plugin, Player player, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        BooleanDialogInput langCheckbox = DialogInput.bool(
                "english",
                Component.text("Gunakan Bahasa Inggris / Use English"),
                isEnglish,
                "true",
                "false"
        );

        ActionButton nextButton = ActionButton.builder(Component.text(isEnglish ? "Next" : "Lanjut"))
                .action(DialogAction.customClick((response, audience) -> {
                    Boolean useEnglish = response.getBoolean("english");
                    String selectedLang = (useEnglish != null && useEnglish) ? "english" : "indonesia";
                    audience.closeDialog();
                    submitLanguageToVelocity(plugin, player, selectedLang);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton quitButton = ActionButton.builder(Component.text(isEnglish ? "Quit" : "Quit / Keluar"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.kick(Component.text(isEnglish ? "§cYou chose to quit." : "§cAnda memilih untuk keluar (Quit)."));
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> bodyList = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(logoBody);
            bodyList.add(DialogBody.plainMessage(Component.text("")));
        }
        bodyList.add(DialogBody.plainMessage(Component.text("§7Pilih Bahasa / Select Language")));
        bodyList.add(DialogBody.plainMessage(Component.text("§eSilakan pilih bahasa Anda untuk melanjutkan.")));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Select Language"))
                        .canCloseWithEscape(false)
                        .body(bodyList)
                        .inputs(List.of(langCheckbox))
                        .build())
                .type(DialogType.multiAction(List.of(nextButton), quitButton, 1))
        );

        player.showDialog(dialog);
    }

    public static void openPreRegTypeDialog(NaturalAuthPaper plugin, Player player, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        ActionButton premiumButton = ActionButton.builder(Component.text(isEnglish ? "Premium Account" : "Akun Premium"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    submitAccountTypeToVelocity(plugin, player, "premium");
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton crackedButton = ActionButton.builder(Component.text(isEnglish ? "Cracked Account" : "Akun Cracked"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    submitAccountTypeToVelocity(plugin, player, "cracked");
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton quitButton = ActionButton.builder(Component.text(isEnglish ? "Quit" : "Quit / Keluar"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.kick(Component.text(isEnglish ? "§cYou chose to quit." : "§cAnda memilih untuk keluar (Quit)."));
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> bodyList = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(logoBody);
            bodyList.add(DialogBody.plainMessage(Component.text("")));
        }

        if (isEnglish) {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§6§lSELECT ACCOUNT TYPE")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§c§lIMPORTANT WARNING:")),
                    DialogBody.plainMessage(Component.text("§eIf you select Premium, your account will be locked to Mojang verification.")),
                    DialogBody.plainMessage(Component.text("§cIf you are actually Cracked, you won't be able to join and must contact admin!")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§7Choose your account type below:"))
            ));
        } else {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§6§lPILIH TIPE AKUN")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§c§lPERINGATAN PENTING:")),
                    DialogBody.plainMessage(Component.text("§eJika memilih Premium, akun Anda akan dikunci dengan verifikasi Mojang.")),
                    DialogBody.plainMessage(Component.text("§cJika Anda sebenarnya Cracked, Anda tidak akan bisa masuk dan harus hubungi admin!")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§7Pilih tipe akun Anda di bawah:"))
            ));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(isEnglish ? "Account Type" : "Tipe Akun"))
                        .canCloseWithEscape(false)
                        .body(bodyList)
                        .build())
                .type(DialogType.multiAction(List.of(premiumButton, crackedButton), quitButton, 2))
        );

        player.showDialog(dialog);
    }

    public static void openPreRegPremiumDialog(NaturalAuthPaper plugin, Player player, String captcha, String language) {
        boolean isEnglish = "english".equalsIgnoreCase(language);

        TextDialogInput captchaInput = DialogInput.text(
                "captcha_input",
                200,
                Component.text(isEnglish ? "Type Captcha" : "Ketik Captcha"),
                true,
                "",
                72,
                null
        );

        ActionButton confirmButton = ActionButton.builder(Component.text(isEnglish ? "Yes, Correct" : "Ya, Benar"))
                .action(DialogAction.customClick((response, audience) -> {
                    String typed = response.getText("captcha_input");
                    if (typed == null || typed.trim().isEmpty()) {
                        audience.closeDialog();
                        player.sendMessage(isEnglish ? "§c§l[!] §r§cCaptcha cannot be empty!" : "§c§l[!] §r§cCaptcha tidak boleh kosong!");
                        submitPreRegPremiumToVelocity(plugin, player, "");
                        return;
                    }

                    audience.closeDialog();
                    submitPreRegPremiumToVelocity(plugin, player, typed);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton backButton = ActionButton.builder(Component.text(isEnglish ? "Back" : "Kembali"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    submitLanguageToVelocity(plugin, player, language);
                }, ClickCallback.Options.builder().build()))
                .build();

        ActionButton quitButton = ActionButton.builder(Component.text(isEnglish ? "Quit" : "Quit / Keluar"))
                .action(DialogAction.customClick((response, audience) -> {
                    audience.closeDialog();
                    player.kick(Component.text(isEnglish ? "§cYou chose to quit." : "§cAnda memilih untuk keluar (Quit)."));
                }, ClickCallback.Options.builder().build()))
                .build();

        List<DialogBody> bodyList = new ArrayList<>();
        DialogBody logoBody = getLogoBody(plugin, player);
        if (logoBody != null) {
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(DialogBody.plainMessage(Component.text("")));
            bodyList.add(logoBody);
            bodyList.add(DialogBody.plainMessage(Component.text("")));
        }

        if (isEnglish) {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§6§lPREMIUM VERIFICATION")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§c§lAre you sure your account is Premium?")),
                    DialogBody.plainMessage(Component.text("§7Tulis Ulang Ini / Rewrite This:")),
                    DialogBody.plainMessage(Component.text("§a§l" + captcha)),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§eType the 6-character captcha below to confirm:"))
            ));
        } else {
            bodyList.addAll(List.of(
                    DialogBody.plainMessage(Component.text("§6§lVERIFIKASI PREMIUM")),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§c§lApakah benar akun Anda Premium?")),
                    DialogBody.plainMessage(Component.text("§7Tulis Ulang Ini / Rewrite This:")),
                    DialogBody.plainMessage(Component.text("§a§l" + captcha)),
                    DialogBody.plainMessage(Component.text("")),
                    DialogBody.plainMessage(Component.text("§eKetik 6 karakter captcha di bawah untuk mengonfirmasi:"))
            ));
        }

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(isEnglish ? "Premium Verification" : "Verifikasi Premium"))
                        .canCloseWithEscape(false)
                        .body(bodyList)
                        .inputs(List.of(captchaInput))
                        .build())
                .type(DialogType.multiAction(List.of(confirmButton, backButton), quitButton, 2))
        );

        player.showDialog(dialog);
    }

    private static void submitAccountTypeToVelocity(NaturalAuthPaper plugin, Player player, String type) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_ACCOUNT_TYPE);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(type);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_ACCOUNT_TYPE to Velocity!");
            e.printStackTrace();
        }
    }

    private static void submitPreRegPremiumToVelocity(NaturalAuthPaper plugin, Player player, String captcha) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_PRE_REG_PREMIUM);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(captcha);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_PRE_REG_PREMIUM to Velocity!");
            e.printStackTrace();
        }
    }
}


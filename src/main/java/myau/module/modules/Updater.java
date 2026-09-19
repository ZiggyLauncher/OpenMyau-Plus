package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import myau.util.UpdateChecker;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the client up to date from the GitHub releases of {@code Repo-Owner/Repo-Name}.
 * <p>
 * Checking and downloading happen on a background thread, so the game never stalls. The running
 * jar is locked by the JVM on Windows and cannot be replaced while the game is open, so the new
 * jar is staged next to it and a small detached command swaps the two once the game has exited -
 * which is also why the swap is the very last thing that happens.
 */
public class Updater extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_OFF = 0;
    private static final int MODE_NOTIFY = 1;
    private static final int MODE_AUTO = 2;

    public final ModeProperty mode = new ModeProperty("Mode", MODE_NOTIFY, new String[]{"Off", "Notify", "Auto"});
    public final TextProperty repoOwner = new TextProperty("Repo-Owner", "ZiggyLauncher");
    public final TextProperty repoName = new TextProperty("Repo-Name", "OpenMyau-Plus");
    public final BooleanProperty checkOnJoin = new BooleanProperty("Check-On-Join", true);

    private volatile boolean checking;
    private volatile boolean checkedThisSession;
    private volatile UpdateChecker.Release available;
    /** The downloaded jar, waiting to be swapped in when the game exits. */
    private volatile File staged;
    private volatile boolean swapScheduled;

    public Updater() {
        super("Updater", true, false, "Checks GitHub for a newer build and can install it on exit");
    }

    @Override
    public void onEnabled() {
        this.checkedThisSession = false;
    }

    /** Where the currently running client jar lives, or null when running from a dev classpath. */
    private static File currentJar() {
        try {
            java.net.URL location = Myau.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            String path = URLDecoder.decode(location.getPath(), StandardCharsets.UTF_8.name());
            if (path.startsWith("/") && path.length() > 3 && path.charAt(2) == ':') {
                path = path.substring(1); // /C:/... -> C:/...
            }
            File file = new File(path);
            return file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar") ? file : null;
        } catch (Exception e) {
            return null;
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (this.checkOnJoin.getValue() && !this.checkedThisSession) {
            this.check(false);
        }
    }

    /**
     * @param manual true when the player asked (always reports the outcome in chat)
     */
    public void check(boolean manual) {
        if (this.mode.getValue() == MODE_OFF && !manual) {
            return;
        }
        if (this.checking) {
            if (manual) {
                ChatUtil.sendFormatted(String.format("%sUpdater: already checking&r", Myau.clientName));
            }
            return;
        }
        this.checking = true;
        this.checkedThisSession = true;
        final String owner = this.repoOwner.getValue();
        final String repo = this.repoName.getValue();
        final boolean auto = this.mode.getValue() == MODE_AUTO;

        Thread worker = new Thread(() -> {
            try {
                UpdateChecker.Release release = UpdateChecker.fetchLatest(owner, repo);
                if (release == null) {
                    if (manual) {
                        ChatUtil.sendFormatted(String.format("%sUpdater: no published release found&r", Myau.clientName));
                    }
                    return;
                }
                if (!UpdateChecker.isNewer(release.tag, Myau.version)) {
                    if (manual) {
                        ChatUtil.sendFormatted(String.format("%sUpdater: you are on the latest build (&a%s&r)&r",
                                Myau.clientName, Myau.version));
                    }
                    return;
                }
                this.available = release;
                ChatUtil.sendFormatted(String.format("%sUpdater: &a%s&r is available (you have &7%s&r)&r",
                        Myau.clientName, release.tag, Myau.version));
                if (auto) {
                    this.stage(release);
                } else {
                    ChatUtil.sendFormatted(String.format("%s&7Run &o.update install&7 to download it&r", Myau.clientName));
                }
            } catch (Exception e) {
                if (manual) {
                    ChatUtil.sendFormatted(String.format("%sUpdater: check failed (&c%s&r)&r", Myau.clientName, e.getMessage()));
                }
            } finally {
                this.checking = false;
            }
        }, "Myau-Updater");
        worker.setDaemon(true);
        worker.start();
    }

    /** Downloads the release beside the current jar, ready for the swap on exit. */
    public void install() {
        UpdateChecker.Release release = this.available;
        if (release == null) {
            ChatUtil.sendFormatted(String.format("%sUpdater: nothing to install - check first&r", Myau.clientName));
            return;
        }
        if (this.staged != null) {
            ChatUtil.sendFormatted(String.format("%sUpdater: &a%s&r is ready, it installs when you quit&r",
                    Myau.clientName, release.tag));
            return;
        }
        Thread worker = new Thread(() -> this.stage(release), "Myau-Updater-Download");
        worker.setDaemon(true);
        worker.start();
    }

    private void stage(UpdateChecker.Release release) {
        File current = currentJar();
        if (current == null) {
            ChatUtil.sendFormatted(String.format("%sUpdater: can't locate the client jar, skipping&r", Myau.clientName));
            return;
        }
        File target = new File(current.getParentFile(), "myau-update.jar");
        try {
            ChatUtil.sendFormatted(String.format("%sUpdater: downloading &a%s&r...&r", Myau.clientName, release.tag));
            UpdateChecker.download(release, target);
            this.staged = target;
            ChatUtil.sendFormatted(String.format("%sUpdater: &a%s&r downloaded - it installs when you quit the game&r",
                    Myau.clientName, release.tag));
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sUpdater: download failed (&c%s&r)&r", Myau.clientName, e.getMessage()));
            if (target.exists()) {
                target.delete();
            }
        }
    }

    public boolean hasStagedUpdate() {
        return this.staged != null && this.staged.isFile();
    }

    /**
     * Last thing before the game closes: hand the swap to a detached shell that waits for this
     * process to release the jar, replaces it, and deletes itself from the picture. Called from
     * {@link Myau#onShutdownRequested()}.
     */
    public void applyOnExit() {
        if (this.swapScheduled || !this.hasStagedUpdate()) {
            return;
        }
        File current = currentJar();
        File update = this.staged;
        if (current == null || update == null || !update.isFile()) {
            return;
        }
        this.swapScheduled = true;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder builder;
            if (os.contains("win")) {
                // ping is the portable "sleep" on Windows; the loop retries while the jar is locked.
                String command = String.format(
                        "ping -n 3 127.0.0.1 >nul & for /l %%i in (1,1,10) do (move /y \"%s\" \"%s\" >nul 2>&1 && exit) & ping -n 2 127.0.0.1 >nul",
                        update.getAbsolutePath(), current.getAbsolutePath());
                builder = new ProcessBuilder("cmd", "/c", command);
            } else {
                String command = String.format(
                        "sleep 2; for i in 1 2 3 4 5; do mv -f '%s' '%s' && break; sleep 1; done",
                        update.getAbsolutePath().replace("'", "'\\''"),
                        current.getAbsolutePath().replace("'", "'\\''"));
                builder = new ProcessBuilder("sh", "-c", command);
            }
            builder.directory(current.getParentFile());
            builder.start();
        } catch (Exception e) {
            this.swapScheduled = false;
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.hasStagedUpdate()) {
            return new String[]{"Ready"};
        }
        return new String[]{this.mode.getModeString()};
    }
}

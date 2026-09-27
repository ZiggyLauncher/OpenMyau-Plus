package myau.platform;

/**
 * What Myau+'s version-independent code needs from the running game.
 * <p>
 * Every Minecraft version Myau+ supports provides one of these. Shared code calls it through
 * {@link Platforms}; it never names a Minecraft class itself, which is what lets the same
 * modules run on 1.8.9 and on versions whose classes look nothing like it.
 * <p>
 * Methods are added here only when shared code genuinely needs them, and each one is phrased in
 * terms the shared code cares about rather than mirroring any one version's API.
 */
public interface Platform {
    /**
     * A key code's name as it should appear to the user, e.g. {@code "R"} or {@code "MOUSE4"}.
     * Returns an empty string when nothing is bound.
     */
    String keyName(int keyCode);

    /**
     * A module was switched on or off through {@code Module.toggle()} - that is, by the user
     * rather than by code. The implementation plays the toggle sound and shows the on-screen
     * notification, both of which are client features rather than module behaviour.
     */
    void onModuleToggled(String moduleName, boolean enabled);
}

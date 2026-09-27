package myau.platform;

/**
 * Holds the {@link Platform} for the version Myau+ is running on.
 * <p>
 * The per-version module installs one during start-up. Until then - and in headless tests, which
 * have no game at all - a do-nothing platform stands in, so shared code can be exercised without
 * a client behind it.
 */
public final class Platforms {
    private static volatile Platform platform = new NoopPlatform();

    private Platforms() {
    }

    public static Platform get() {
        return platform;
    }

    public static void set(Platform platform) {
        Platforms.platform = platform == null ? new NoopPlatform() : platform;
    }

    /** The stand-in used before a version installs its own, and in tests. */
    private static final class NoopPlatform implements Platform {
        @Override
        public String keyName(int keyCode) {
            return keyCode == 0 ? "" : String.valueOf(keyCode);
        }

        @Override
        public void onModuleToggled(String moduleName, boolean enabled) {
        }
    }
}

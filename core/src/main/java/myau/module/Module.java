package myau.module;

import myau.platform.Platforms;

public abstract class Module {
    protected final String name;
    protected final String description;
    protected final boolean defaultEnabled;
    protected final int defaultKey;
    protected final boolean defaultHidden;
    protected boolean enabled;
    protected int key;
    protected boolean hidden;

    public Module(String name, boolean enabled) {
        this(name, enabled, false, "");
    }

    public Module(String name, boolean enabled, boolean hidden) {
        this(name, enabled, hidden, "");
    }

    public Module(String name, boolean enabled, boolean hidden, String description) {
        this.name = name;
        this.description = description;
        this.enabled = this.defaultEnabled = enabled;
        this.key = this.defaultKey = 0;
        this.hidden = this.defaultHidden = hidden;
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public String formatModule() {
        return String.format(
                "%s%s &r(%s&r)",
                this.key == 0 ? "" : String.format("&l[%s] &r", Platforms.get().keyName(this.key)),
                this.name,
                this.enabled ? "&a&lON" : "&c&lOFF"
        );
    }

    public String[] getSuffix() {
        return new String[0];
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) {
                this.onEnabled();
            } else {
                this.onDisabled();
            }
        }
    }

    public boolean toggle() {
        boolean enabled = !this.enabled;
        this.setEnabled(enabled);
        if (this.enabled == enabled) {
            // The toggle sound and the on-screen notification are client features, not module
            // behaviour, so they live behind the platform rather than in here.
            Platforms.get().onModuleToggled(this.getName(), this.enabled);
            return true;
        } else {
            return false;
        }
    }

    public int getKey() {
        return this.key;
    }

    public void setKey(int integer) {
        this.key = integer;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public void setHidden(boolean boolean1) {
        this.hidden = boolean1;
    }

    public void onEnabled() {
    }

    public void onDisabled() {
    }

    public void verifyValue(String string) {
    }

    public boolean shouldKeepSprint() {
        return false;
    }
}

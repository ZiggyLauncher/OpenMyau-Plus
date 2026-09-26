package myau.ui.impl.clickgui.vape;

import myau.property.properties.TextProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Something the list editor frame can show and change: entries with add, remove and pick. */
interface ListSource {
    String title();

    /** Icon in the frame's title bar. */
    String icon();

    /** Blocked lists mark their entries red, allowed ones green. */
    boolean blocked();

    List<String> entries();

    void add(String entry);

    void remove(String entry);

    /** Whether entries can be removed from the list editor. */
    boolean removable();

    /** Entries shown with their mark filled in. */
    boolean selected(String entry);

    /** A click on the entry itself. */
    void clicked(String entry);

    /** A comma-separated item list setting ({@code ItemListProperty}). */
    final class CommaList implements ListSource {
        private final TextProperty property;
        private final String title;
        private final boolean blocked;

        CommaList(TextProperty property) {
            this.property = property;
            String name = property.getName().toLowerCase(Locale.ROOT);
            this.blocked = name.contains("blacklist") || name.contains("blocked");
            if (name.contains("whitelist")) {
                this.title = "Whitelist";
            } else if (name.contains("blacklist")) {
                this.title = "Blacklist";
            } else if (name.contains("allowed")) {
                this.title = "Allowed Items";
            } else if (name.contains("blocked")) {
                this.title = "Blocked Items";
            } else {
                this.title = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).replaceAll("-", " ");
            }
        }

        @Override
        public String title() {
            return this.title;
        }

        @Override
        public String icon() {
            return this.blocked ? "blockedicon" : "allowedicon";
        }

        @Override
        public boolean blocked() {
            return this.blocked;
        }

        @Override
        public List<String> entries() {
            List<String> entries = new ArrayList<String>();
            String value = this.property.getValue();
            if (value == null) {
                return entries;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
            return entries;
        }

        @Override
        public void add(String entry) {
            String trimmed = entry.replace(",", " ").trim();
            if (trimmed.isEmpty()) {
                return;
            }
            List<String> entries = this.entries();
            for (String existing : entries) {
                if (existing.equalsIgnoreCase(trimmed)) {
                    return;
                }
            }
            entries.add(trimmed);
            this.write(entries);
        }

        @Override
        public void remove(String entry) {
            List<String> entries = this.entries();
            if (entries.remove(entry)) {
                this.write(entries);
            }
        }

        private void write(List<String> entries) {
            StringBuilder builder = new StringBuilder();
            for (String entry : entries) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(entry);
            }
            this.property.setValue(builder.toString());
        }

        @Override
        public boolean removable() {
            return true;
        }

        @Override
        public boolean selected(String entry) {
            return true;
        }

        @Override
        public void clicked(String entry) {
        }
    }
}

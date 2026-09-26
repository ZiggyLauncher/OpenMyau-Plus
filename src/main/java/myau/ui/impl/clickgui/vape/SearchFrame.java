package myau.ui.impl.clickgui.vape;

import myau.Myau;
import myau.module.Module;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The floating search bar ({@code ModuleSearchFrame}), Vape's default search style: a slim bar
 * pinned to the top centre of the screen. Typing lists every module whose name contains the
 * text, as ordinary module rows.
 */
final class SearchFrame extends VFrame {
    private String query = "";
    private String builtFor = "";

    SearchFrame(VapeClickGui gui) {
        super(gui);
        this.background = VapeTheme.DARKEST;
        this.draggable = false;
        this.visible = true;
        this.positioned = true;
        this.setHeader(new SearchHeader());
    }

    @Override
    String name() {
        return "ModuleSearch";
    }

    @Override
    void update(double mouseX, double mouseY) {
        this.x = Math.floor(VapeRender.screenWidth() / 2.0 - this.width / 2.0);
        this.y = 7.0;
        if (!this.query.equals(this.builtFor)) {
            this.rebuild();
        }
        super.update(mouseX, mouseY);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
    }

    private void rebuild() {
        this.builtFor = this.query;
        this.components.clear();
        this.scroll = 0.0;
        if (this.query.isEmpty() || Myau.moduleManager == null) {
            return;
        }
        String wanted = normalize(this.query);
        List<Module> matches = new ArrayList<Module>();
        for (Module module : Myau.moduleManager.allModules()) {
            if (normalize(module.getName()).contains(wanted)) {
                matches.add(module);
            }
        }
        for (Module module : matches) {
            // Search finds modules hidden from their category too, as the original's does.
            this.gui.addModuleRow(this, module).alwaysShown = true;
        }
    }

    void clear() {
        this.query = "";
    }

    void focusInput() {
        this.gui.focus((TextEditable) this.header);
    }

    /** {@code ModuleSearchFrameHeader}: the input with a magnifier, or a cross once typed in. */
    private final class SearchHeader extends VFrame.Header implements TextEditable {
        private final IconButton search = this.icon(new IconButton("newsearch", 0.9));
        private final IconButton close = this.icon(new IconButton("newclose", 0.7));

        @Override
        double preferredHeight() {
            return 16.0;
        }

        private boolean focused() {
            return SearchFrame.this.gui.focused == this;
        }

        @Override
        void render(double mouseX, double mouseY) {
            String query = SearchFrame.this.query;
            VapeFont font = font(0.9);
            double textX = this.x + 6.0;
            double textY = this.y + this.height / 2.0 - font.height() / 2.0;
            double available = this.width - 6.0 - 14.0;
            double textWidth = font.width(query);
            double shift = textWidth > available ? textWidth - available : 0.0;
            VapeRender.pushScissor(textX, this.y, available, this.height);
            try {
                font.draw(query, textX - shift, textY, VapeTheme.TEXT_BRIGHT);
                if (this.focused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                    VapeFont caret = font(1.2);
                    caret.draw("|", textX - shift + textWidth, this.y + this.height / 2.0 - caret.height() / 2.0,
                            VapeTheme.TEXT_BRIGHT);
                }
            } finally {
                VapeRender.popScissor();
            }
            if (!query.isEmpty()) {
                this.search.visible = false;
                this.close.visible = true;
                this.close.x = this.x + this.width - 13.0;
                this.close.y = this.y + 1.0;
                this.close.width = 10.0;
                this.close.height = this.height;
            } else {
                this.close.visible = false;
                this.search.visible = true;
                this.search.x = this.x + this.width - 14.0;
                this.search.y = this.y + 0.5;
                this.search.width = 10.0;
                this.search.height = this.height;
            }
            this.renderIcons();
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (this.close.contains(mouseX, mouseY) && button == 0) {
                SearchFrame.this.query = "";
                return true;
            }
            if (button == 1) {
                SearchFrame.this.query = "";
            }
            SearchFrame.this.gui.focus(this);
            return true;
        }

        @Override
        public boolean editKey(char typedChar, int keyCode) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                SearchFrame.this.gui.focus(null);
                return true;
            }
            if (keyCode == Keyboard.KEY_BACK) {
                if (!SearchFrame.this.query.isEmpty()) {
                    SearchFrame.this.query = SearchFrame.this.query.substring(0, SearchFrame.this.query.length() - 1);
                }
                return true;
            }
            if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && SearchFrame.this.query.length() < 32) {
                SearchFrame.this.query += typedChar;
            }
            return true;
        }

        @Override
        public void focusLost() {
        }
    }
}

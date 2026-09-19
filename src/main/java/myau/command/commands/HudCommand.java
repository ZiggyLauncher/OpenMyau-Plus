package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.module.Module;
import myau.module.modules.HudEditor;
import myau.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * {@code .hud} - opens the HUD editor.
 */
public class HudCommand extends Command {
    public HudCommand() {
        super(new ArrayList<>(Arrays.asList("hud", "hudeditor")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        Module module = Myau.moduleManager.getModule(HudEditor.class);
        if (!(module instanceof HudEditor)) {
            ChatUtil.sendFormatted(String.format("%sHUD editor is unavailable&r", Myau.clientName));
            return;
        }
        module.setEnabled(true);
    }
}

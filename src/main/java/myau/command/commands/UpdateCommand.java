package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.module.Module;
import myau.module.modules.Updater;
import myau.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * {@code .update} - check for a new build, or download the one that was found.
 */
public class UpdateCommand extends Command {
    public UpdateCommand() {
        super(new ArrayList<>(Arrays.asList("update", "updater")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        Module module = Myau.moduleManager.getModule(Updater.class);
        if (!(module instanceof Updater)) {
            ChatUtil.sendFormatted(String.format("%sUpdater is unavailable&r", Myau.clientName));
            return;
        }
        Updater updater = (Updater) module;
        String sub = args.size() >= 2 ? args.get(1).toLowerCase(Locale.ROOT) : "check";
        switch (sub) {
            case "check":
                updater.check(true);
                return;
            case "install":
            case "download":
                updater.install();
                return;
            default:
                ChatUtil.sendFormatted(String.format("%sUsage: .%s &ocheck&r/&oinstall&r", Myau.clientName,
                        args.get(0).toLowerCase(Locale.ROOT)));
        }
    }
}

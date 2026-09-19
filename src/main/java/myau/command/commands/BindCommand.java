package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.module.Module;
import myau.util.ChatUtil;
import myau.util.KeyBindUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class BindCommand extends Command {
    public BindCommand() {
        super(new ArrayList<>(Arrays.asList("bind", "b")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 3) {
            if (args.size() == 2 && (args.get(1).equalsIgnoreCase("l") || args.get(1).equalsIgnoreCase("list"))) {
                List<Module> modules = Myau.moduleManager.allModules().stream().filter(module -> module.getKey() != KeyBindUtil.NONE).collect(Collectors.toList());
                if (modules.isEmpty()) {
                    ChatUtil.sendFormatted(String.format("%sNo binds&r", Myau.clientName));
                } else {
                    ChatUtil.sendFormatted(String.format("%sBinds:&r", Myau.clientName));
                    for (Module module : modules) {
                        ChatUtil.sendFormatted(String.format("%s»&r %s&r", module.isHidden() ? "&8" : "&7", module.formatModule()));
                    }
                }
            } else {
                String command = args.get(0).toLowerCase(Locale.ROOT);
                ChatUtil.sendFormatted(
                        String.format(
                                "%sUsage: .%s <&omodule&r> <&okey&r> | .%s <&omodule&r> &onone&r | .%s &olist&r",
                                Myau.clientName, command, command, command
                        )
                );
                ChatUtil.sendFormatted(
                        String.format("%sKeys: any keyboard key (&oR&r, &oRSHIFT&r, &oF6&r) or mouse button (&oMOUSE4&r, &oMOUSE5&r, &oRMB&r, &oMMB&r)&r", Myau.clientName)
                );
            }
            return;
        }

        int keyIndex = KeyBindUtil.parseKey(args.get(2));
        if (keyIndex == Integer.MIN_VALUE) {
            ChatUtil.sendFormatted(String.format("%sUnknown key (&o%s&r) - try a key name like &oR&r or a mouse button like &oMOUSE4&r&r", Myau.clientName, args.get(2)));
            return;
        }

        if (!args.get(1).equals("*")) {
            Module module = Myau.moduleManager.getModule(args.get(1));
            if (module == null) {
                ChatUtil.sendFormatted(String.format("%sModule not found (&o%s&r)&r", Myau.clientName, args.get(1)));
                return;
            }
            module.setKey(keyIndex);
            if (keyIndex == KeyBindUtil.NONE) {
                ChatUtil.sendFormatted(String.format("%sUnbind &o%s&r", Myau.clientName, module.getName()));
            } else {
                ChatUtil.sendFormatted(
                        String.format("%sBound &o%s&r to &l[%s]&r", Myau.clientName, module.getName(), KeyBindUtil.getKeyName(keyIndex))
                );
            }
        } else {
            for (Module module : Myau.moduleManager.allModules()) {
                module.setKey(keyIndex);
            }
            if (keyIndex == KeyBindUtil.NONE) {
                ChatUtil.sendFormatted(String.format("%sUnbind all modules&r", Myau.clientName));
            } else {
                ChatUtil.sendFormatted(String.format("%sBind all modules to &l[%s]&r", Myau.clientName, KeyBindUtil.getKeyName(keyIndex)));
            }
        }
    }
}

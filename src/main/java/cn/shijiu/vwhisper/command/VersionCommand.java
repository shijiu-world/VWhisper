package cn.shijiu.vwhisper.command;

import cn.shijiu.vwhisper.VWhisper;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

/**
 * {@code /vw version} —— 报一下版本，谁都能敲（查线上装的是哪版时用得上）。
 */
public final class VersionCommand implements SimpleCommand {

    private final VWhisper plugin;

    public VersionCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        plugin.send(source, "&8[&bVWhisper&8] &7版本 " + plugin.version()
                + "，跨服私聊。权限前缀：&fvwhisper.*");
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

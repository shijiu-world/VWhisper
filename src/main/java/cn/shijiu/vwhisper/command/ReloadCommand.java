package cn.shijiu.vwhisper.command;

import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

/**
 * {@code /vw reload} —— 重新读 config.toml。
 *
 * <p>读失败时 VWhisper 会保留旧配置并告诉你原因，不会让插件变成半成品。
 */
public final class ReloadCommand implements SimpleCommand {

    private final VWhisper plugin;

    public ReloadCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        if (!Permissions.require(plugin, source, Permissions.RELOAD, false)) {
            return;
        }
        plugin.reload(source);
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

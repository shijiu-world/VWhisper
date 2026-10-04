package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

/**
 * {@code /spy} —— 私聊窥屏开关（别名 socialspy / msgspy）。
 *
 * <p>需要 {@code vwhisper.spy}（特权节点，必须在 LuckPerms 里显式给）。
 * ⚠️ 状态故意不落盘：代理一重启全部自动关掉，不会因为上次忘了关而继续看别人的私聊。
 */
public final class SpyCommand implements SimpleCommand {

    private final VWhisper plugin;

    public SpyCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.SPY, false)) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final Player me = (Player) source;
        final boolean enable;
        if (args.length >= 1) {
            final String arg = args[0].toLowerCase();
            enable = arg.equals("on") || arg.equals("true") || arg.equals("开");
        } else {
            enable = !plugin.store().isSpying(me.getUniqueId());
        }
        plugin.store().setSpying(me.getUniqueId(), enable);
        plugin.send(source, config.message(enable ? "spy-on" : "spy-off"));
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

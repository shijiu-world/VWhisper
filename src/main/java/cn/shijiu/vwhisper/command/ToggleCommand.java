package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

/**
 * {@code /msgtoggle} —— 自己要不要收私聊（别名 togglemsg / tmsg）。
 *
 * <p>关掉之后别人私聊你会看到"对方关了私聊"；持有 {@code vwhisper.toggle.bypass}
 * 的人（管理组）不受这个限制，还是能发进来。
 */
public final class ToggleCommand implements SimpleCommand {

    private final VWhisper plugin;

    public ToggleCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.TOGGLE, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("players-only"));
            return;
        }
        final Player me = (Player) source;
        final boolean receive;
        if (args.length >= 1) {
            final String arg = args[0].toLowerCase();
            receive = arg.equals("off") || arg.equals("false") || arg.equals("关");
        } else {
            receive = !plugin.store().isReceiving(me.getUniqueId());
        }
        plugin.store().setReceiving(me.getUniqueId(), receive, config.saveToggles());
        plugin.send(source, config.message(receive ? "toggle-on" : "toggle-off"));
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

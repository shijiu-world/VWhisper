package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Locale;

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
            final String arg = args[0].toLowerCase(Locale.ROOT);
            if (arg.equals("off") || arg.equals("false") || arg.equals("关") || arg.equals("no")) {
                receive = false;
            } else if (arg.equals("on") || arg.equals("true") || arg.equals("开") || arg.equals("yes")) {
                receive = true;
            } else {
                // 认不出来就按「开关」处理。
                // ⚠️ 别写成「不是 off 就当 off」——那样 /vw toggle on 会真的关掉接收，
                //    打错一个字也会被静默关掉（这是本插件上线前修掉的一个反向 bug）。
                receive = !plugin.store().isReceiving(me.getUniqueId());
            }
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

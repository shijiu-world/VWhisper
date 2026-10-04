package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.List;
import java.util.Locale;

/**
 * {@code /vwhisper <reload|help|version>} —— 管理命令（别名 VWhisper / vws）。
 *
 * <p>help 谁都能看，reload 需要 {@code vwhisper.reload}。
 * ⚠️ 主名必须小写 —— Velocity 底层 Brigadier 的 literal 节点大小写敏感。
 */
public final class AdminCommand implements SimpleCommand {

    private final VWhisper plugin;

    public AdminCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        final String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "help";
        if (sub.equals("reload")) {
            if (!Permissions.require(plugin, source, Permissions.RELOAD, false)) {
                return;
            }
            plugin.reload(source);
            return;
        }
        if (sub.equals("version") || sub.equals("info")) {
            if (!Permissions.require(plugin, source, Permissions.RELOAD, false)) {
                return;
            }
            plugin.send(source, "&8[&bVWhisper&8] &7版本 " + plugin.version()
                    + "，跨服私聊。权限前缀：&fvwhisper.*");
            return;
        }
        help(source);
    }

    private void help(final CommandSource source) {
        plugin.send(source, "&8===== &bVWhisper &8=====");
        plugin.send(source, "&7/msg <玩家> <消息> &8- &f发私聊（别名 tell/w/m/pm/whisper）");
        plugin.send(source, "&7/reply <消息> &8- &f回复最近聊过的人（别名 r）");
        plugin.send(source, "&7/msgtoggle &8- &f开关接收私聊（别名 togglemsg/tmsg）");
        plugin.send(source, "&7/ignore <玩家> &8- &f屏蔽/取消屏蔽");
        plugin.send(source, "&7/spy &8- &f私聊窥屏（需要 vwhisper.spy）");
        plugin.send(source, "&7/vwhisper reload &8- &f重载配置（需要 vwhisper.reload）");
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("reload", "help", "version");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

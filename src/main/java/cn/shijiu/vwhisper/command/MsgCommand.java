package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /msg <玩家> <消息>} —— 跨服私聊主命令。
 *
 * <p>别名 tell / w / m / pm / whisper 都在 VWhisper 里注册。
 * ⚠️ 主命令名小写（Velocity 底层 Brigadier 的 literal 节点大小写敏感，注册成大写
 *    会导致敲小写时报"命令不存在"并被转发给后端）。
 */
public final class MsgCommand implements SimpleCommand {

    private final VWhisper plugin;

    public MsgCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.MSG, config.allowByDefault())) {
            return;
        }
        if (args.length < 2) {
            // 走顶层快捷命令（/msg）时提示 /msg <玩家> <消息>，走 /vw msg 时提示 /vw msg …
            plugin.send(source, config.message("usage-msg",
                    "label", config.label(invocation.alias(), "msg")));
            return;
        }
        final String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.service().send(source, args[0], message);
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final String[] args = invocation.arguments();
        if (args.length == 0) {
            return plugin.onlineNames("");
        }
        if (args.length == 1) {
            return plugin.onlineNames(args[0].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    /** 权限自己在 execute 里判 —— 这样没权限的人看到的是"你没权限"，而不是"命令不存在"。 */
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

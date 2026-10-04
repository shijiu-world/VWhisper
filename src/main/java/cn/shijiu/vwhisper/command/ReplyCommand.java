package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /reply <消息>} —— 回复最近一次跟自己聊过的人。
 *
 * <p>目标是按 UUID 记的：对方改过名、或者换到别的服去了都还找得到。
 * 唯一回不了的情况是人已经下线 —— 那就老实重发一条 /msg。
 */
public final class ReplyCommand implements SimpleCommand {

    private final VWhisper plugin;

    public ReplyCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.REPLY, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("usage-reply",
                    "label", config.label(invocation.alias(), "reply")));
            return;
        }
        if (args.length < 1) {
            plugin.send(source, config.message("usage-reply",
                    "label", config.label(invocation.alias(), "reply")));
            return;
        }
        final Player me = (Player) source;
        final UUID targetUuid = plugin.store().contact(me.getUniqueId());
        if (targetUuid == null) {
            plugin.send(source, config.message("no-reply-target"));
            return;
        }
        final Optional<Player> target = plugin.proxy().getPlayer(targetUuid);
        if (!target.isPresent()) {
            plugin.send(source, config.message("reply-target-offline"));
            return;
        }
        plugin.service().send(source, target.get().getUsername(),
                String.join(" ", Arrays.copyOfRange(args, 0, args.length)));
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

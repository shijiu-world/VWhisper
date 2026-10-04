package cn.shijiu.vwhisper.command;


import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /ignore <玩家>} —— 屏蔽 / 取消屏蔽（按 UUID 记，改名也不受影响）。
 *
 * <p>屏蔽效果只对普通玩家生效：持有 {@code vwhisper.ignore.bypass} 的人依然能发进来，
 * 免得管理有急事找不到人。
 */
public final class IgnoreCommand implements SimpleCommand {

    private final VWhisper plugin;

    public IgnoreCommand(final VWhisper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        final Configuration config = plugin.configuration();
        if (!Permissions.require(plugin, source, Permissions.IGNORE, config.allowByDefault())) {
            return;
        }
        if (!(source instanceof Player)) {
            plugin.send(source, config.message("usage-ignore",
                    "label", config.label(invocation.alias(), "ignore")));
            return;
        }
        if (args.length < 1) {
            plugin.send(source, config.message("usage-ignore",
                    "label", config.label(invocation.alias(), "ignore")));
            return;
        }
        final Player me = (Player) source;
        final WhisperService.Lookup lookup = plugin.service().findPlayer(args[0]);
        if (lookup.player() == null) {
            plugin.send(source, config.message(lookup.ambiguous() ? "ambiguous-target" : "player-not-found",
                    "target", args[0]));
            return;
        }
        final Player target = lookup.player();
        if (target.getUniqueId().equals(me.getUniqueId())) {
            plugin.send(source, config.message("ignore-self"));
            return;
        }
        final boolean added = plugin.store().toggleIgnore(me.getUniqueId(), target.getUniqueId(),
                config.saveIgnores());
        plugin.send(source, config.message(added ? "ignore-added" : "ignore-removed",
                "target", target.getUsername()));
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

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }
}

package cn.shijiu.vwhisper.command;

import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Permissions;
import cn.shijiu.vwhisper.VWhisper;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /vwhisper <子命令> …} —— 全插件只有这一个命令入口（别名默认 {@code /vw}）。
 *
 * <p>之所以全收进来：代理上同一个命令名只能注册一次，以前 /msg、/reply、/spy 各自注册，
 * 跟别的插件（CMI、Essentials 之类）抢短命令时只能一个个改别名；现在只占一个名字，
 * 剩下全是自己的子命令，想怎么叫都行，也不会误伤后端子服的命令。
 *
 * <p>子命令的别名在 {@code config.toml} 的 {@code [commands]} 里配，等号左边是子命令主名。
 */
public final class RootCommand implements SimpleCommand {

    /** 一条子命令：名字、用法、说明，以及权限（决定它在帮助里出不出现）。 */
    private static final class Entry {
        final String name;
        final String usage;
        final String description;
        final String permission;
        final boolean basic;
        final SimpleCommand command;

        Entry(final String name, final String usage, final String description,
              final String permission, final boolean basic, final SimpleCommand command) {
            this.name = name;
            this.usage = usage;
            this.description = description;
            this.permission = permission;
            this.basic = basic;
            this.command = command;
        }

        /** 这个人有没有资格在帮助里看到这一条。 */
        boolean visible(final CommandSource source, final Configuration config) {
            return permission == null
                    || Permissions.has(source, permission, basic && config.allowByDefault());
        }
    }

    private final VWhisper plugin;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public RootCommand(final VWhisper plugin) {
        this.plugin = plugin;
        add("msg", new MsgCommand(plugin), "msg <玩家> <消息>", "给在线的人发私聊（跨服）",
                Permissions.MSG, true);
        add("reply", new ReplyCommand(plugin), "reply <消息>", "回复最近聊过的人",
                Permissions.REPLY, true);
        add("toggle", new ToggleCommand(plugin), "toggle [on|off]", "开关自己收不收私聊",
                Permissions.TOGGLE, true);
        add("ignore", new IgnoreCommand(plugin), "ignore <玩家>", "屏蔽/取消屏蔽某人",
                Permissions.IGNORE, true);
        add("spy", new SpyCommand(plugin), "spy [on|off]", "私聊窥屏（管理视角）",
                Permissions.SPY, false);
        add("reload", new ReloadCommand(plugin), "reload", "重载配置",
                Permissions.RELOAD, false);
        add("version", new VersionCommand(plugin), "version", "显示插件版本", null, false);
    }

    private void add(final String name, final SimpleCommand command, final String usage,
                     final String description, final String permission, final boolean basic) {
        entries.put(name, new Entry(name, usage, description, permission, basic, command));
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        final String[] args = invocation.arguments();
        if (args.length == 0) {
            help(source);
            return;
        }
        final String name = args[0].toLowerCase(Locale.ROOT);
        if (name.equals("help") || name.equals("?")) {
            help(source);
            return;
        }
        final Entry entry = resolve(name);
        if (entry == null) {
            // #label# 由 Configuration 自动填（跟着 root 别名变）
            plugin.send(source, plugin.configuration().message("unknown-subcommand", "sub", args[0]));
            return;
        }
        entry.command.execute(new SubInvocation(invocation, tail(args)));
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        final Configuration config = plugin.configuration();
        final String[] args = invocation.arguments();
        if (args.length <= 1) {
            // 正在敲子命令名：补全所有他看得见的子命令（主名 + 别名）
            final String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            final List<String> out = new ArrayList<>();
            for (final Entry entry : entries.values()) {
                if (!entry.visible(invocation.source(), config)) {
                    continue;
                }
                if (entry.name.startsWith(prefix)) {
                    out.add(entry.name);
                }
                for (final String alias : config.subAliases().getOrDefault(entry.name, List.of())) {
                    if (alias.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(alias);
                    }
                }
            }
            return out;
        }
        final Entry entry = resolve(args[0]);
        if (entry == null) {
            return List.of();
        }
        // 后面的参数交给子命令自己补（它看到的参数要剥掉子命令名这一层）
        return entry.command.suggest(new SubInvocation(invocation, tail(args)));
    }

    /** 权限一律在子命令里判 —— 没权限的人看到的是"你没权限"，而不是"命令不存在"。 */
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return true;
    }

    // ------------------------------------------------------------------
    // 帮助
    // ------------------------------------------------------------------

    private void help(final CommandSource source) {
        final Configuration config = plugin.configuration();
        plugin.send(source, "&8===== &bVWhisper &8=====");
        for (final Entry entry : entries.values()) {
            if (!entry.visible(source, config)) {
                continue;
            }
            plugin.send(source, "&7" + config.label() + " " + entry.usage + " &8- &f" + entry.description);
        }
        plugin.send(source, "&7" + config.label() + " help &8- &f看这个列表");
    }

    /** 按名字（或别名）找子命令；找不到返回 null。 */
    private Entry resolve(final String name) {
        final Entry direct = entries.get(name);
        if (direct != null) {
            return direct;
        }
        for (final Map.Entry<String, List<String>> e : plugin.configuration().subAliases().entrySet()) {
            for (final String alias : e.getValue()) {
                if (alias.equalsIgnoreCase(name)) {
                    return entries.get(e.getKey());
                }
            }
        }
        return null;
    }

    private static String[] tail(final String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /** 把 invocation 剥掉第一个参数交给子命令 —— source 和 alias 原样透传。 */
    private static final class SubInvocation implements Invocation {
        private final Invocation parent;
        private final String[] args;

        SubInvocation(final Invocation parent, final String[] args) {
            this.parent = parent;
            this.args = args;
        }

        @Override
        public CommandSource source() {
            return parent.source();
        }

        @Override
        public String[] arguments() {
            return args;
        }

        @Override
        public String alias() {
            return parent.alias();
        }
    }
}

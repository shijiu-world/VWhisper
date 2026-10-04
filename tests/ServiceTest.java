import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.Store;
import cn.shijiu.vwhisper.TomlLite;
import cn.shijiu.vwhisper.VWhisper;
import cn.shijiu.vwhisper.WhisperService;
import cn.shijiu.vwhisper.command.MsgCommand;
import cn.shijiu.vwhisper.command.RootCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand.Invocation;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * VWhisper 集成测试：把 Velocity 的 API 用动态代理桩掉，跑真实的 WhisperService / MsgCommand。
 *
 * <p>验证的是那些「肉眼看不见的业务规则」：权限闸门、接收开关、屏蔽、窥屏、冷却、
 * /reply 记忆、颜色模式、服务器名单。
 */
public class ServiceTest {

    // ==================================================================
    // 桩件
    // ==================================================================

    /** 假玩家：记着自己收到了什么、哪些权限明确给/明确拒。 */
    static final class Fake {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final String server;
        final Set<String> allowed;
        final Set<String> denied;
        final List<Component> inbox = new ArrayList<>();
        final CommandSource source;
        final Player player;

        Fake(final String name, final String server, final Set<String> allowed, final Set<String> denied) {
            this.name = name;
            this.server = server;
            this.allowed = allowed;
            this.denied = denied;
            final InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId": return uuid;
                    case "getUsername": return name;
                    case "getCurrentServer": return server == null ? Optional.empty() : Optional.of(server(server));
                    case "getPermissionValue": return permissionValue(String.valueOf(args[0]));
                    case "hasPermission": return allowed.contains(String.valueOf(args[0]));
                    case "sendMessage":
                        if (args != null && args[0] instanceof Component) {
                            inbox.add((Component) args[0]);
                        }
                        return null;
                    case "toString": return "Fake(" + name + ")";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return Defaults.forType(method.getReturnType());
                }
            };
            this.player = (Player) Proxy.newProxyInstance(loader(), new Class<?>[]{Player.class}, handler);
            // 同一个桩：Player 接口本身就继承了 CommandSource，source instanceof Player 才成立
            this.source = (CommandSource) this.player;
        }

        private Tristate permissionValue(final String node) {
            if (allowed.contains(node)) {
                return Tristate.TRUE;
            }
            if (denied.contains(node)) {
                return Tristate.FALSE;
            }
            return Tristate.UNDEFINED;
        }

        /** 收到过的所有纯文本（拼成一坨方便 contains 判断）。 */
        String allText() {
            final StringBuilder builder = new StringBuilder();
            for (final Component c : inbox) {
                builder.append(PlainComponentSerializer.plain().serialize(c)).append('\n');
            }
            return builder.toString();
        }

        boolean saw(final String needle) {
            return allText().contains(needle);
        }

        void clear() {
            inbox.clear();
        }
    }

    static ClassLoader loader() {
        return ServiceTest.class.getClassLoader();
    }

    /** 各种返回类型的兜底值 —— 动态代理返回类型不兼容会炸。 */
    static final class Defaults {
        static Object forType(final Class<?> type) {
            if (!type.isPrimitive()) {
                if (type == Optional.class) {
                    return Optional.empty();
                }
                if (type == List.class || type == Set.class) {
                    return List.of();
                }
                if (type == Map.class) {
                    return Map.of();
                }
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            return null;
        }
    }

    private static ServerConnection server(final String name) {
        return (ServerConnection) Proxy.newProxyInstance(loader(), new Class<?>[]{ServerConnection.class},
                (proxy, method, args) -> "getServerInfo".equals(method.getName())
                        ? info(name) : Defaults.forType(method.getReturnType()));
    }

    // ServerInfo 是 final class，不能动态代理，直接 new 一个真的
    private static ServerInfo info(final String name) {
        return new ServerInfo(name, new java.net.InetSocketAddress("127.0.0.1", 25566));
    }

    private static final Map<String, Fake> registry = new HashMap<>();
    private static final List<Object> everyone = new ArrayList<>();

    private static Fake add(final Fake fake) {
        registry.put(fake.name, fake);
        everyone.add(fake.player);
        return fake;
    }

    // ==================================================================
    // 搭环境
    // ==================================================================

    static VWhisper plugin;
    static WhisperService service;
    static Store store;

    private static void setup() throws Exception {
        final ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(loader(),
                new Class<?>[]{ProxyServer.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "getAllPlayers": return new ArrayList<>(everyone);
                        case "getPlayer": {
                            for (final Fake fake : registry.values()) {
                                if (args[0] instanceof UUID
                                        ? fake.uuid.equals(args[0])
                                        : fake.name.equalsIgnoreCase(String.valueOf(args[0]))) {
                                    return Optional.of(fake.player);
                                }
                            }
                            return Optional.empty();
                        }
                        case "getPlayerCount": return everyone.size();
                        case "getScheduler": return stubOf(com.velocitypowered.api.scheduler.Scheduler.class);
                        case "getCommandManager": return stubOf(com.velocitypowered.api.command.CommandManager.class);
                        case "getPluginManager": return stubOf(com.velocitypowered.api.plugin.PluginManager.class);
                        case "getEventManager": return stubOf(com.velocitypowered.api.event.EventManager.class);
                        default: return Defaults.forType(method.getReturnType());
                    }
                });
        final Logger logger = (Logger) Proxy.newProxyInstance(loader(), new Class<?>[]{Logger.class},
                (p, method, args) -> Defaults.forType(method.getReturnType()));
        final Path dir = Files.createTempDirectory("vwhisper-test");
        final Constructor<VWhisper> ctor = VWhisper.class.getConstructor(
                ProxyServer.class, Logger.class, Path.class);
        plugin = ctor.newInstance(proxy, logger, dir);
        final Field storeField = VWhisper.class.getDeclaredField("store");
        storeField.setAccessible(true);
        store = (Store) storeField.get(plugin);
        final Method serviceMethod = VWhisper.class.getDeclaredMethod("service");
        serviceMethod.setAccessible(true);
        service = (WhisperService) serviceMethod.invoke(plugin);
        reconfig(defaultOverrides());
    }

    private static final Map<String, Object> STUBS = new HashMap<>();

    private static Object stubOf(final Class<?> type) {
        final String key = type.getName();
        if (STUBS.containsKey(key)) {
            return STUBS.get(key);
        }
        final Object stub = Proxy.newProxyInstance(loader(), new Class<?>[]{type},
                (p, method, args) -> Defaults.forType(method.getReturnType()));
        STUBS.put(key, stub);
        return stub;
    }

    /** 换一份配置（玩家不重建）—— 每条测试都要能自己控制闸门。 */
    private static void reconfig(final Map<String, Object> overrides) throws Exception {
        final Field field = VWhisper.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(plugin, newConfig(overrides));
    }

    private static Map<String, Object> defaultOverrides() {
        final Map<String, Object> overrides = new HashMap<>();
        overrides.put("advanced.log-to-console", Boolean.FALSE);
        overrides.put("sound.enabled", Boolean.FALSE);
        overrides.put("messages.prefix", "");
        overrides.put("messages.self-message", "&c不能给自己发私聊。");
        overrides.put("messages.target-off", "&c对方关了私聊。");
        overrides.put("messages.target-ignored", "&c对方屏蔽了你。");
        overrides.put("messages.player-not-found", "&c找不到人。");
        overrides.put("messages.cooldown", "&c还要等。");
        overrides.put("messages.server-denied", "&c这服不能用私聊。");
        overrides.put("messages.no-permission", "&c你没权限。");
        overrides.put("format.sender", "&7我→#target#: #message#");
        overrides.put("format.receiver", "&7#sender#→我: #message#");
        overrides.put("format.spy", "&7[spy] #sender#→#target#: #message#");
        return overrides;
    }

    private static Configuration newConfig(final Map<String, Object> overrides) throws Exception {
        final Map<String, Object> map = new HashMap<>();
        try (InputStream in = ServiceTest.class.getClassLoader().getResourceAsStream("config.toml")) {
            map.putAll(TomlLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        map.putAll(overrides);
        final Constructor<Configuration> ctor = Configuration.class.getDeclaredConstructor(Map.class);
        ctor.setAccessible(true);
        return ctor.newInstance(map);
    }

    // ==================================================================
    // 断言
    // ==================================================================

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private static void check(final String name, final boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failures.add(name);
            System.out.println("  ❌ " + name);
        }
    }

    private static Set<String> perms(final String... nodes) {
        return Set.of(nodes);
    }

    private static final Set<String> BASIC = perms(
            "vwhisper.msg", "vwhisper.reply", "vwhisper.toggle", "vwhisper.ignore");

    /** 默认当玩家敲的是主命令（/vw）—— 想模拟顶层快捷命令就用 invocationAs("msg", …)。 */
    private static Invocation invocation(final CommandSource source, final String... arguments) {
        return invocationAs("vw", source, arguments);
    }

    private static Invocation invocationAs(final String alias, final CommandSource source,
                                           final String... arguments) {
        return (Invocation) Proxy.newProxyInstance(loader(), new Class<?>[]{Invocation.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "source": return source;
                        case "arguments": return arguments;
                        case "alias": return alias;
                        default: return Defaults.forType(method.getReturnType());
                    }
                });
    }

    // ==================================================================
    // 正式开跑
    // ==================================================================

    public static void main(final String[] args) throws Exception {
        setup();

        final Fake alice = add(new Fake("Alice", "survival", BASIC, perms()));
        final Fake bob = add(new Fake("Bob", "industry", BASIC, perms()));
        final Fake carol = add(new Fake("Carol", "lobby", BASIC, perms()));

        // ---- 1. 正常私聊（两人不同服，这才是"跨服"） ----
        check("发给在线玩家成功", service.send(alice.source, "Bob", "你好啊"));
        check("对方收得到（人在另一个服）", bob.saw("Alice→我: 你好啊"));
        check("自己看到的是 sender 格式", alice.saw("我→Bob: 你好啊"));
        check("无关的第三人收不到", !carol.saw("你好啊"));
        check("/reply 记忆两边都记着", store.contact(alice.uuid).equals(bob.uuid)
                && store.contact(bob.uuid).equals(alice.uuid));

        // ---- 2. 给自己发 / 找不到人 ----
        alice.clear();
        check("不能给自己发", !service.send(alice.source, "Alice", "自言自语") && alice.saw("不能给自己发私聊"));
        alice.clear();
        check("找不到人时不发送", !service.send(alice.source, "Nobody", "喂") && alice.saw("找不到人"));

        // ---- 3. 接收开关 /msgtoggle ----
        store.setReceiving(bob.uuid, false, false);
        alice.clear();
        bob.clear();
        check("对方关了私聊：被拦下", !service.send(alice.source, "Bob", "还在吗") && alice.saw("对方关了私聊"));
        check("拦下的消息没到对方手里", !bob.saw("还在吗"));
        final Fake admin = add(new Fake("Admin", "lobby", perms("vwhisper.msg", "vwhisper.toggle.bypass"), perms()));
        admin.clear();
        bob.clear();
        check("有 toggle.bypass 的管理能发进去", service.send(admin.source, "Bob", "开门"));
        check("   并且对方真的收到了", bob.saw("Admin→我: 开门"));
        store.setReceiving(bob.uuid, true, false);

        // ---- 4. 屏蔽 /ignore ----
        store.toggleIgnore(bob.uuid, alice.uuid, false);
        alice.clear();
        bob.clear();
        check("被屏蔽的人发不进去", !service.send(alice.source, "Bob", "理我一下") && alice.saw("对方屏蔽了你"));
        final Fake bigAdmin = add(new Fake("BigAdmin", "lobby", perms("vwhisper.msg", "vwhisper.ignore.bypass"), perms()));
        check("有 ignore.bypass 的人不受屏蔽影响", service.send(bigAdmin.source, "Bob", "紧急通知"));
        check("   并且对方真的收到了", bob.saw("BigAdmin→我: 紧急通知"));
        store.toggleIgnore(bob.uuid, alice.uuid, false);

        // ---- 5. 窥屏 /spy ----
        final Fake watcher = add(new Fake("Watcher", "lobby", perms("vwhisper.spy"), perms()));
        store.setSpying(watcher.uuid, true);
        watcher.clear();
        alice.clear();
        bob.clear();
        service.send(alice.source, "Bob", "悄悄话");
        check("开窥屏的人看得到别人的私聊", watcher.saw("[spy] Alice→Bob: 悄悄话"));
        watcher.clear();
        service.send(admin.source, "Bob", "第二次");
        check("没开 spy 的人看不到", !carol.saw("[spy]"));
        final Fake stealthy = add(new Fake("Stealth", "lobby", perms("vwhisper.msg", "vwhisper.spy.bypass"), perms()));
        watcher.clear();
        service.send(stealthy.source, "Bob", "私密谈话");
        check("有 spy.bypass 的人不被窥屏", !watcher.saw("私密谈话"));
        store.setSpying(watcher.uuid, false);

        // ---- 6. 冷却 ----
        reconfig(defaultOverrides());
        final Map<String, Object> cooldown = defaultOverrides();
        cooldown.put("cooldown.seconds", 60L);
        reconfig(cooldown);
        // 用全新的人：冷却是按人记的，前面测过的 Alice 早已经"发过"了
        final Fake chatter = add(new Fake("Chatter", "survival", BASIC, perms()));
        check("第一次发送放行", service.send(chatter.source, "Bob", "第一条"));
        chatter.clear();
        check("冷却内第二条被拦", !service.send(chatter.source, "Bob", "第二条") && chatter.saw("还要等"));
        final Fake speedy = add(new Fake("Speedy", "survival", perms("vwhisper.msg", "vwhisper.cooldown.bypass"), perms()));
        check("有 cooldown.bypass 不受限制", service.send(speedy.source, "Bob", "我口才好"));
        reconfig(defaultOverrides());

        // ---- 7. 服务器名单 ----
        final Map<String, Object> filtered = defaultOverrides();
        filtered.put("servers.mode", "blacklist");
        filtered.put("servers.list", List.of("survival"));
        reconfig(filtered);
        alice.clear();
        check("在黑名单服里发不出私聊",
                !service.send(alice.source, "Bob", "还能聊吗") && alice.saw("这服不能用私聊"));
        reconfig(defaultOverrides());

        // ---- 8. 消息内容颜色 ----
        final Map<String, Object> parse = defaultOverrides();
        parse.put("colors.mode", "keep");
        reconfig(parse);
        bob.clear();
        service.send(alice.source, "Bob", "&c红的");
        check("没颜色权限 + keep：&c 原样显示", bob.saw("&c红的"));
        final Map<String, Object> strip = defaultOverrides();
        strip.put("colors.mode", "strip");
        reconfig(strip);
        bob.clear();
        service.send(alice.source, "Bob", "&c红的");
        check("strip 模式：颜色码被删掉", bob.saw("Alice→我: 红的") && !bob.saw("&c"));
        final Map<String, Object> always = defaultOverrides();
        always.put("colors.mode", "parse");
        reconfig(always);
        bob.clear();
        final Fake painter = add(new Fake("Painter", "survival", perms("vwhisper.msg", "vwhisper.msg.color"), perms()));
        service.send(painter.source, "Bob", "&c红的");
        check("有 vwhisper.msg.color 的人：真的上色了", hasColor(bob));
        reconfig(defaultOverrides());

        // ---- 9. 走一遍 /msg 命令：权限闸门 ----
        final Map<String, Object> strict = defaultOverrides();
        strict.put("permissions.allow-by-default", Boolean.FALSE);
        reconfig(strict);
        final Fake poor = add(new Fake("Poor", "survival", perms(), perms()));
        poor.clear();
        new MsgCommand(plugin).execute(invocation(poor.source, "Bob", "嗨"));
        check("没 vwhisper.msg 权限：命令直接挡住", poor.saw("你没权限"));
        check("   并且一个字都没发出去", !bob.saw("嗨"));
        alice.clear();
        new MsgCommand(plugin).execute(invocation(alice.source, "Bob"));
        check("有权限但参数不够时给用法提示", alice.saw("/vw msg"));
        reconfig(defaultOverrides());

        // ---- 10. Tab 补全 ----
        final List<String> suggestions = new MsgCommand(plugin).suggest(invocation(alice.source, "b"));
        check("/vw msg 的 Tab 补全给出在线玩家", suggestions.contains("Bob"));

        // ---- 11. 统一入口 /vw <子命令> ----
        final RootCommand root = new RootCommand(plugin);
        alice.clear();
        bob.clear();
        root.execute(invocation(alice.source, "msg", "Bob", "走子命令"));
        check("/vw msg 真的发得出去", bob.saw("Alice→我: 走子命令"));
        check("  并且发送者自己看到回显", alice.saw("我→Bob: 走子命令"));

        alice.clear();
        bob.clear();
        root.execute(invocation(alice.source, "m", "Bob", "走别名"));
        check("/vw m（msg 的别名）也能发", bob.saw("Alice→我: 走别名"));

        alice.clear();
        bob.clear();
        root.execute(invocation(alice.source, "r", "回一下"));
        check("/vw r（reply 的别名）能回复", bob.saw("Alice→我: 回一下"));

        alice.clear();
        root.execute(invocation(alice.source, "nonsense"));
        check("不存在的子命令给提示", alice.saw("没有") && alice.saw("nonsense"));

        alice.clear();
        root.execute(invocation(alice.source));
        check("不带参数给帮助", alice.saw("VWhisper") && alice.saw("/vw msg"));
        check("帮助里不含没权限的子命令", !alice.saw("/vw spy"));
        alice.clear();
        root.execute(invocation(alice.source, "help"));
        check("/vw help 也是帮助", alice.saw("/vw msg"));

        final List<String> subs = root.suggest(invocation(alice.source, ""));
        check("补全给出子命令名", subs.contains("msg") && subs.contains("reply"));
        check("补全也给出子命令别名", subs.contains("m") && subs.contains("r"));
        check("没权限的子命令不进补全", !subs.contains("spy"));

        final List<String> delegated = root.suggest(invocation(alice.source, "msg", "b"));
        check("子命令名之后交给子命令补全", delegated.contains("Bob"));

        // ---- 12. 顶层快捷命令（[shortcuts]：/msg、/w 那些）----
        check("root 能按主名找到子命令", root.lookup("msg") != null);
        check("root 能按别名找到子命令", root.lookup("w") != null);
        check("找不到的子命令返回 null", root.lookup("nonsense") == null);
        check("配置里默认接管 /msg", "msg".equals(plugin.configuration().shortcuts().get("msg")));
        check("配置里默认接管 /r → reply", "reply".equals(plugin.configuration().shortcuts().get("r")));

        // 顶层命令注册的就是子命令本身，直接跑一遍等于玩家敲 /msg
        alice.clear();
        bob.clear();
        new MsgCommand(plugin).execute(invocationAs("msg", alice.source, "Bob", "从 /msg 进来"));
        check("/msg 直接发得出去", bob.saw("Alice→我: 从 /msg 进来"));

        alice.clear();
        new MsgCommand(plugin).execute(invocationAs("msg", alice.source, "Bob"));
        check("从 /msg 进来时用法提示写的是 /msg", alice.saw("/msg <玩家> <消息>"));
        check("  并且不会串成 /vw msg", !alice.saw("/vw msg"));

        alice.clear();
        root.execute(invocation(alice.source, "msg", "Bob"));
        check("从 /vw msg 进来时用法提示写的是 /vw msg", alice.saw("/vw msg <玩家> <消息>"));

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部 " + passed + " 条断言通过 ✅");
        } else {
            System.out.println("❌ 失败 " + failures.size() + " 条：");
            failures.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    private static boolean hasColor(final Fake fake) {
        for (final Component component : fake.inbox) {
            if (component.color() != null) {
                return true;
            }
            for (final Component child : component.children()) {
                if (child.color() != null) {
                    return true;
                }
            }
        }
        return false;
    }
}

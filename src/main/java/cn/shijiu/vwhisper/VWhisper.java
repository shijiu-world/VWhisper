package cn.shijiu.vwhisper;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import cn.shijiu.vwhisper.command.RootCommand;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * VWhisper —— 跨服私聊。
 *
 * <p>整个插件只装在代理端，子服一个插件都不用装：消息是代理对着
 * {@code target.sendMessage()} 直接发出去的，后端压根不知道有这条消息，
 * 所以人在哪个服都收得到 —— 这就是"跨服"的全部秘密。
 */
@Plugin(
        id = "vwhisper",
        name = "VWhisper",
        version = "1.0.0",
        description = "跨服私聊：/msg /reply /spy /msgtoggle /ignore",
        authors = {"拾玖世界"}
)
public final class VWhisper {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Store store;
    private final WhisperService service;

    private volatile Configuration config;
    /** 上一次读到的 config.toml 修改时间，热重载用。 */
    private volatile long lastModified;

    @Inject
    public VWhisper(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.store = new Store(dataDirectory, logger);
        this.service = new WhisperService(this, proxy, logger, store);
        this.config = Configuration.defaults();
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        // 先读配置（构造函数里已经塞了内置默认值，读不出来也有东西用），再注册命令 ——
        // 别名是从 config.toml 的 [commands] 里取的。
        store.load();
        reload(null);
        registerCommands();
        startAutoReload();

        logger.info("[vwhisper] VWhisper 已就绪 —— 跨服私聊开着，命令 " + config.label()
                + " msg（别名可在 [commands] 里改）。子服不用装任何东西。");
    }

    /** 有人下线：把 /reply 记忆、窥屏状态都清掉，别占着内存也别留下"还能回复"的假象。 */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final Player player = event.getPlayer();
        store.forget(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    /**
     * 只注册一个命令：{@code /vwhisper}，别名（默认 {@code /vw}）来自 config.toml 的
     * {@code [commands] root}。私聊、屏蔽、窥屏、重载全是它的子命令 —— 这样代理上只占一个
     * 命令名，不会跟别的插件抢 /msg、/w 之类，也不会误伤后端子服自己的命令。
     */
    private void registerCommands() {
        register("vwhisper", new RootCommand(this), config.rootAliases());
    }

    private void register(final String name, final SimpleCommand command, final List<String> aliases) {
        // ⚠️ 主名一律小写：Velocity 底层走 Brigadier，literal 节点大小写敏感，
        //    注册成大写的话，敲小写会"命令不存在"，还会被转发给后端
        final CommandManager manager = proxy.getCommandManager();
        final CommandMeta meta = manager.metaBuilder(name)
                .aliases(aliases.toArray(new String[0]))
                .plugin(this)
                .build();
        manager.register(meta, command);
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /** 重新读 config.toml。出错时保留旧配置 —— 宁可用旧的，也不让插件变成半成品。 */
    public void reload(final CommandSource feedback) {
        final Configuration.LoadResult result = Configuration.load(dataDirectory, logger);
        if (result.error() != null) {
            logger.warn("[vwhisper] config.toml 没读出来，继续用旧配置：" + result.error());
            if (feedback != null) {
                send(feedback, config.message("reload-failed", "reason", result.error()));
            }
            return;
        }
        config = result.config();
        lastModified = configMtime();
        reportConfig();
        if (feedback != null) {
            send(feedback, config.message("reloaded"));
        }
    }

    /** 起服 / reload 之后把关键配置打一遍 —— 省得改了半天不知道到底生没生效。 */
    private void reportConfig() {
        final Configuration.ServerFilter filter = config.filter();
        logger.info("[vwhisper] 私聊内容颜色：" + config.colorMode()
                + "（strip=剥 / parse=解析 / keep=原样；有 vwhisper.msg.color 权限的一律解析）");
        logger.info("[vwhisper] 子服名单：" + (filter.isWhitelist() ? "白名单" : "黑名单")
                + (filter.servers().isEmpty() ? "（空 = 全都参与）" : " " + String.join(", ", filter.servers())));
        logger.info("[vwhisper] 提示音：" + (config.soundEnabled() ? config.soundName() : "关"));
        logger.info("[vwhisper] 冷却：" + (config.cooldownSeconds() <= 0
                ? "不限" : config.cooldownSeconds() + " 秒"));
    }

    private long configMtime() {
        try {
            return Files.getLastModifiedTime(dataDirectory.resolve("config.toml")).toMillis();
        } catch (final Exception e) {
            return -1L;
        }
    }

    /** 改了配置不想敲命令就用这个（advanced.auto-reload = true）。按修改时间判断，没变不读。 */
    private void startAutoReload() {
        proxy.getScheduler().buildTask(this, () -> {
            if (!config.autoReload()) {
                return;
            }
            final long now = configMtime();
            if (now > 0 && now != lastModified) {
                logger.info("[vwhisper] 发现 config.toml 变了，自动重载。");
                reload(null);
            }
        // 周期取自当前的 advanced.auto-reload-interval-seconds：这个间隔只在起服时读一次，
        // 中途改了要重启代理才生效；开关（auto-reload）则是每次跑 task 都看，随时可改。
        }).delay(3, TimeUnit.SECONDS)
                .repeat(Math.max(1L, config.autoReloadIntervalSeconds()), TimeUnit.SECONDS)
                .schedule();
    }

    // ------------------------------------------------------------------
    // 给命令 / 服务用的小接口
    // ------------------------------------------------------------------

    /** 发一条带 & 颜色码的文本。 */
    public void send(final CommandSource source, final String legacy) {
        source.sendMessage(ChatColors.SERIALIZER.deserialize(legacy));
    }

    /** Tab 补全用：在线玩家名（按前缀过滤，小写比较）。 */
    public List<String> onlineNames(final String lowerPrefix) {
        final List<String> names = new ArrayList<>();
        final String prefix = lowerPrefix == null ? "" : lowerPrefix;
        for (final Player player : proxy.getAllPlayers()) {
            if (player.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(player.getUsername());
            }
        }
        return names;
    }

    public String version() {
        return proxy.getPluginManager().fromInstance(this)
                .map(container -> container.getDescription().getVersion().orElse("unknown"))
                .orElse("unknown");
    }

    public Configuration configuration() {
        return config;
    }

    public Store store() {
        return store;
    }

    public WhisperService service() {
        return service;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    Path dataDirectory() {
        return dataDirectory;
    }

    Logger logger() {
        return logger;
    }
}

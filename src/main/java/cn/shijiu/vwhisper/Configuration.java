package cn.shijiu.vwhisper;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * config.toml 的读取与校验。
 *
 * <p>铁律：**读失败的时候保留上一次的配置**，绝不让插件变成半成品。
 * 所以这里所有取值都带默认值，{@link #load} 出错时返回 error 而不是 null 之后裸奔。
 */
public final class Configuration {

    /** 加载结果：config 可能为旧的（出错时），error 不为空说明这次没能读成。 */
    public static final class LoadResult {
        final Configuration config;
        final String error;

        LoadResult(final Configuration config, final String error) {
            this.config = config;
            this.error = error;
        }

        public Configuration config() {
            return config;
        }

        public String error() {
            return error;
        }
    }

    // ---------------- 服务器名单 ----------------
    private final ServerFilter filter;    // ---------------- 颜色 ----------------
    private final boolean allowByDefault;
    private final String colorMode;
    // ---------------- 格式 ----------------
    private final boolean miniMessage;
    private final String formatSender;
    private final String formatReceiver;
    private final String formatSpy;
    private final String formatConsole;
    // ---------------- 悬停提示 / 点击动作 ----------------
    private final boolean tooltipEnabled;
    private final String tooltipPrefix;
    private final String tooltipHover;
    private final String tooltipSuggest;
    private final boolean tooltipCopy;
    private final String tooltipCopyHover;
    private final DateTimeFormatter tooltipTimeFormat;
    private final ZoneId tooltipZone;
    // ---------------- 提示语 ----------------
    private final String prefix;
    private final Map<String, String> messages;
    // ---------------- 其它 ----------------
    /** 能不能给自己发私聊（自言自语）。 */
    private final boolean allowSelfMessage;
    private final long cooldownSeconds;
    /** 提示音总闸；false 时三档都不响（各档自己的 enabled 也要为真才响）。 */
    private final boolean soundEnabled;
    /** 收到私聊的人听到的。 */
    private final SoundCue soundTarget;
    /** 发出私聊的人听到的回执音。 */
    private final SoundCue soundSender;
    /** 开着 /spy 窥屏的人听到的。 */
    private final SoundCue soundSpy;
    private final boolean saveIgnores;
    private final boolean saveToggles;
    private final boolean autoReload;
    private final int autoReloadIntervalSeconds;
    private final boolean logToConsole;
    /** 主命令 /vwhisper 的别名（默认 ["vw"]）。 */
    private final List<String> rootAliases;
    /** 子命令别名：子命令主名 -> 别名列表（在 /vw 后面敲的那个词）。 */
    private final Map<String, List<String>> subAliases;
    /** 顶层快捷命令：命令名 -> 子命令主名（注册到 Velocity 顶层，会盖掉子服同名的命令）。 */
    private final Map<String, String> shortcuts;

    private Configuration(final Map<String, Object> m) {
        this.filter = new ServerFilter(
                TomlLite.string(m, "servers.mode", "blacklist"),
                TomlLite.list(m, "servers.list", Collections.emptyList()));

        this.allowByDefault = TomlLite.bool(m, "permissions.allow-by-default", true);

        String mode = TomlLite.string(m, "colors.mode", "keep").toLowerCase(Locale.ROOT);
        if (!mode.equals("strip") && !mode.equals("parse") && !mode.equals("keep")) {
            mode = "keep";
        }
        this.colorMode = mode;

        this.miniMessage = TomlLite.bool(m, "format.minimessage", false);
        this.formatSender = TomlLite.string(m, "format.sender", "&8[&7我 &8→ &7#target#&8]&r #message#");
        this.formatReceiver = TomlLite.string(m, "format.receiver", "&8[&7#sender# &8→ &7我&8]&r #message#");
        this.formatSpy = TomlLite.string(m, "format.spy", "&8[&cSpy&8] &7#sender# &8→ &7#target#&8:&r #message#");
        this.formatConsole = TomlLite.string(m, "format.console", "&8[&c控制台 &8→ &7我&8]&r #message#");

        this.tooltipEnabled = TomlLite.bool(m, "Tooltip.enabled", true);
        this.tooltipPrefix = TomlLite.string(m, "Tooltip.prefix", ChatTooltip.DEFAULT_PREFIX);
        this.tooltipHover = TomlLite.string(m, "Tooltip.hover", ChatTooltip.DEFAULT_HOVER);
        this.tooltipSuggest = TomlLite.string(m, "Tooltip.suggest", ChatTooltip.DEFAULT_SUGGEST);
        this.tooltipCopy = TomlLite.bool(m, "Tooltip.copy", true);
        this.tooltipCopyHover = TomlLite.string(m, "Tooltip.copy-hover", ChatTooltip.DEFAULT_COPY_HOVER);
        this.tooltipTimeFormat = ChatTooltip.compileTimeFormat(
                TomlLite.string(m, "Tooltip.time-format", ChatTooltip.DEFAULT_TIME_PATTERN));
        this.tooltipZone = ChatTooltip.parseZone(TomlLite.string(m, "Tooltip.time-zone", ""));

        this.allowSelfMessage = TomlLite.bool(m, "general.allow-self-message", true);

        this.prefix = TomlLite.string(m, "messages.prefix", "&8[&b私聊&8]&r");
        final Map<String, String> messages = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("messages.") && !e.getKey().equals("messages.prefix")) {
                messages.put(e.getKey().substring("messages.".length()), String.valueOf(e.getValue()));
            }
        }
        this.messages = Collections.unmodifiableMap(messages);

        this.cooldownSeconds = Math.max(0L, TomlLite.integer(m, "cooldown.seconds", 0L));
        this.soundEnabled = TomlLite.bool(m, "sound.enabled", true);

        // 🔴 升级兜底：v1.2.0 之前只有一组 [sound]（enabled / name / volume / pitch），
        //    它其实就是「接收者」那一档。新键优先、老键兜底 —— 老配置升级上来音效不变
        //    （否则服主自己改过的音效名会被静默重置成默认值，日志里还看不出来）。
        //    sender / spy 两档是新加的，默认关着，不打扰。
        final String legacyName = TomlLite.string(m, "sound.name", SoundCue.DEFAULT_NAME);
        final float legacyVolume = (float) TomlLite.decimal(m, "sound.volume", 1.0D);
        final float legacyPitch = (float) TomlLite.decimal(m, "sound.pitch", 1.0D);

        this.soundTarget = SoundCue.of(
                TomlLite.bool(m, "sound.target.enabled", this.soundEnabled),
                TomlLite.string(m, "sound.target.name", legacyName),
                (float) TomlLite.decimal(m, "sound.target.volume", legacyVolume),
                (float) TomlLite.decimal(m, "sound.target.pitch", legacyPitch),
                TomlLite.string(m, "sound.target.source", "player"));
        this.soundSender = SoundCue.of(
                TomlLite.bool(m, "sound.sender.enabled", false),
                TomlLite.string(m, "sound.sender.name", SoundCue.DEFAULT_NAME),
                (float) TomlLite.decimal(m, "sound.sender.volume", 1.0D),
                (float) TomlLite.decimal(m, "sound.sender.pitch", 1.0D),
                TomlLite.string(m, "sound.sender.source", "player"));
        this.soundSpy = SoundCue.of(
                TomlLite.bool(m, "sound.spy.enabled", false),
                TomlLite.string(m, "sound.spy.name", SoundCue.DEFAULT_NAME),
                (float) TomlLite.decimal(m, "sound.spy.volume", 1.0D),
                (float) TomlLite.decimal(m, "sound.spy.pitch", 1.0D),
                TomlLite.string(m, "sound.spy.source", "player"));

        this.saveIgnores = TomlLite.bool(m, "storage.save-ignores", true);
        this.saveToggles = TomlLite.bool(m, "storage.save-toggles", true);

        this.autoReload = TomlLite.bool(m, "advanced.auto-reload", false);
        this.autoReloadIntervalSeconds =
                (int) Math.max(1L, TomlLite.integer(m, "advanced.auto-reload-interval-seconds", 3L));
        this.logToConsole = TomlLite.bool(m, "advanced.log-to-console", true);

        // [commands] 段：root 是主命令的别名，其余每个键都是「子命令主名 = [别名...]」
        final Map<String, List<String>> commands = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("commands.") && e.getValue() instanceof List) {
                @SuppressWarnings("unchecked")
                final List<String> list = new ArrayList<>((List<String>) e.getValue());
                commands.put(e.getKey().substring("commands.".length()), list);
            }
        }
        final List<String> root = commands.remove("root");
        this.rootAliases = root == null ? Collections.singletonList("vw") : root;
        this.subAliases = Collections.unmodifiableMap(commands);

        // [shortcuts] 段：把某个子命令直接注册成顶层命令（"命令名" = "子命令主名"）
        // 值统一小写，注册时拿它去 RootCommand 里找执行器
        final Map<String, String> sc = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().startsWith("shortcuts.") && e.getValue() instanceof String) {
                final String target = ((String) e.getValue()).trim().toLowerCase(Locale.ROOT);
                if (!target.isEmpty()) {
                    sc.put(e.getKey().substring("shortcuts.".length()).toLowerCase(Locale.ROOT), target);
                }
            }
        }
        this.shortcuts = Collections.unmodifiableMap(sc);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    public static LoadResult load(final Path dataDirectory, final Logger logger) {
        final Path file = dataDirectory.resolve("config.toml");
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(file)) {
                copyDefault(logger, file);
            }
            final Map<String, Object> parsed = TomlLite.parse(file);
            return new LoadResult(new Configuration(parsed), null);
        } catch (final Exception e) {
            logger.warn("[vwhisper] 读取 config.toml 失败：" + e);
            return new LoadResult(null, String.valueOf(e));
        }
    }

    /**
     * 用 jar 里自带的默认 config.toml 造一份配置。
     *
     * <p>⚠️ 这个方法是在插件**构造函数**里调的，以前读不出来就抛
     * {@code IllegalStateException}，结果插件连加载都不成功、连一句日志都没有。
     * 其实构造函数里每个取值都写了默认值，拿张空表也能造出一份完整配置，
     * 所以这里失败时兜底返回内置默认 —— 保住「先起来再说」。
     */
    public static Configuration defaults() {
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("config.toml")) {
            if (in == null) {
                throw new IOException("jar 里找不到默认 config.toml");
            }
            return new Configuration(TomlLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            return new Configuration(Collections.emptyMap());
        }
    }

    private static void copyDefault(final Logger logger, final Path file) throws IOException {
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("config.toml")) {
            if (in == null) {
                throw new IOException("jar 里找不到默认 config.toml");
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("[vwhisper] 已生成默认配置文件：" + file.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    public ServerFilter filter() {
        return filter;
    }

    public boolean allowByDefault() {
        return allowByDefault;
    }

    /** 允不允许给自己发私聊（默认允许）。关掉时敲 /msg 自己会收到 messages.self-message。 */
    public boolean allowSelfMessage() {
        return allowSelfMessage;
    }

    public String colorMode() {
        return colorMode;
    }

    public boolean miniMessage() {
        return miniMessage;
    }

    public String formatSender() {
        return formatSender;
    }

    public String formatReceiver() {
        return formatReceiver;
    }

    public String formatSpy() {
        return formatSpy;
    }

    public String formatConsole() {
        return formatConsole;
    }

    // ------------------------------------------------------------------
    // 悬停提示 / 点击动作（[Tooltip]）
    // ------------------------------------------------------------------

    /** 悬停提示 / 点击动作的总开关；关掉时两档都不挂。 */
    public boolean tooltipEnabled() {
        return tooltipEnabled;
    }

    /**
     * 悬停提示前面那截固定内容（默认先亮一个发送者所在服）。
     *
     * <p>存的是<b>模板</b>（里面可以有 {@code #server#} / {@code {player}} / {@code {time}}），
     * 换成真值是在 {@link ChatTooltip#apply} 里跟 hover 一起做的。
     */
    public String tooltipPrefix() {
        return tooltipPrefix;
    }

    /** 整条消息上的悬停文本（默认「发送时间: xx:xx:xx」）。 */
    public String tooltipHover() {
        return tooltipHover;
    }

    /** 点一下填进聊天框的命令（默认 {@code " /msg{player} "}）。 */
    public String tooltipSuggest() {
        return tooltipSuggest;
    }

    /** 正文（{@code #message#}）点一下要不要能复制。 */
    public boolean tooltipCopy() {
        return tooltipCopy;
    }

    /** 鼠标放在正文上的提示（默认「复制该文本」）；留空 = 只不显示提示，点击照样复制。 */
    public String tooltipCopyHover() {
        return tooltipCopyHover;
    }

    /** {@code {time}} 的时间格式；写错时按 HH:mm:ss 兜底。 */
    public DateTimeFormatter tooltipTimeFormat() {
        return tooltipTimeFormat;
    }

    /** {@code {time}} 用的时区；留空 = 服务器系统时区。 */
    public ZoneId tooltipZone() {
        return tooltipZone;
    }

    /**
     * 取一条提示语（已经拼好 prefix）。缺了就返回兜底文本，不返回 null。
     *
     * @param args 依次替换 {@code #name#} 形式的占位符（"占位符名", "值", …）
     */
    public String message(final String key, final Object... args) {
        String text = messages.get(key);
        if (text == null) {
            text = "&c(缺少配置项 messages." + key + ")";
        }
        // 先替换调用方传进来的 —— 允许用 ("label", "/msg") 覆盖掉默认的 #label#，
        // 这样从顶层快捷命令进来时，用法提示显示的就是玩家真正敲的那个命令
        for (int i = 0; i + 1 < args.length; i += 2) {
            text = text.replace("#" + args[i] + "#", String.valueOf(args[i + 1]));
        }
        // #label# 最后兜底填成实际命令名（/vw 之类）—— 改了别名提示语也跟着变，不用逐个改
        if (text.contains("#label#")) {
            text = text.replace("#label#", label());
        }
        return prefix + text;
    }

    /** 提示语里显示成什么命令名 —— 取配置的第一个主命令别名，没配就用 /vwhisper。 */
    public String label() {
        final String first = rootAliases.isEmpty() ? null : rootAliases.get(0);
        return "/" + (first == null || first.isEmpty() ? "vwhisper" : first);
    }

    /**
     * 提示语里显示成什么命令名 —— 按玩家实际敲的那个命令来。
     *
     * @param alias 玩家敲的命令名（Velocity 的 {@code Invocation#alias()}，不含斜杠）
     * @param sub   子命令主名
     * @return 走顶层快捷命令时是 {@code /msg} 这种，走主命令时是 {@code /vw msg} 这种
     */
    public String label(final String alias, final String sub) {
        if (alias == null || alias.isEmpty()) {
            return label() + " " + sub;
        }
        if (shortcuts.containsKey(alias.toLowerCase(Locale.ROOT))) {
            return "/" + alias;
        }
        return "/" + alias + " " + sub;
    }

    /** 不带 prefix 的原始提示语 —— 少数场景（比如要拼换行）用。 */
    public String rawMessage(final String key) {
        final String text = messages.get(key);
        return text == null ? key : text;
    }

    public long cooldownSeconds() {
        return cooldownSeconds;
    }

    /** 提示音总闸；false 时三档都不响。 */
    public boolean soundEnabled() {
        return soundEnabled;
    }

    /** 收到私聊的人听到的那一档。 */
    public SoundCue soundTarget() {
        return soundTarget;
    }

    /** 发出私聊的人听到的回执音。 */
    public SoundCue soundSender() {
        return soundSender;
    }

    /** 开着 /spy 窥屏的人听到的那一档。 */
    public SoundCue soundSpy() {
        return soundSpy;
    }

    public boolean saveIgnores() {
        return saveIgnores;
    }

    public boolean saveToggles() {
        return saveToggles;
    }

    public boolean autoReload() {
        return autoReload;
    }

    public int autoReloadIntervalSeconds() {
        return autoReloadIntervalSeconds;
    }

    public boolean logToConsole() {
        return logToConsole;
    }

    /** 主命令 /vwhisper 的别名（默认 ["vw"]）；写空 list 就只用 /vwhisper。 */
    public List<String> rootAliases() {
        return rootAliases;
    }

    /** 子命令别名表：键是子命令主名（msg / reply / …），值是在 /vw 后面能用的别名。 */
    public Map<String, List<String>> subAliases() {
        return subAliases;
    }

    public Map<String, String> shortcuts() {
        return shortcuts;
    }

    // ------------------------------------------------------------------
    // 服务器名单
    // ------------------------------------------------------------------

    /**
     * 子服黑白名单。
     *
     * <p>失效模式偏向「放行」：模式名写错会退回黑名单（= 全通），
     * 服名取不到（比如人还在登录中）也按「参与」处理 —— 宁可多发，不可吞掉。
     */
    public static final class ServerFilter {
        private final boolean whitelist;
        private final List<String> servers;

        ServerFilter(final String mode, final List<String> servers) {
            final String normalized = mode.trim().toLowerCase(Locale.ROOT);
            this.whitelist = normalized.equals("whitelist");
            this.servers = TomlLite.lowercase(new ArrayList<>(servers));
        }

        /**
         * 这个服能不能用私聊。
         *
         * @param server  Velocity 里的服务器名；null（比如在控制台、或人不在任何服上）按放行处理
         */
        public boolean allows(final String server) {
            if (server == null) {
                return true;
            }
            final boolean listed = servers.contains(server.trim().toLowerCase(Locale.ROOT));
            return whitelist == listed;
        }

        public boolean isWhitelist() {
            return whitelist;
        }

        public List<String> servers() {
            return servers;
        }
    }
}

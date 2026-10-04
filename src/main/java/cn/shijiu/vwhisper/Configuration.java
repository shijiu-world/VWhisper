package cn.shijiu.vwhisper;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final ServerFilter filter;
    // ---------------- 颜色 ----------------
    private final boolean allowByDefault;
    private final String colorMode;
    private final String gradientPermission;
    // ---------------- 格式 ----------------
    private final boolean miniMessage;
    private final String formatSender;
    private final String formatReceiver;
    private final String formatSpy;
    private final String formatConsole;
    // ---------------- 提示语 ----------------
    private final String prefix;
    private final Map<String, String> messages;
    // ---------------- 其它 ----------------
    private final long cooldownSeconds;
    private final boolean soundEnabled;
    private final String soundName;
    private final float soundVolume;
    private final float soundPitch;
    private final boolean saveIgnores;
    private final boolean saveToggles;
    private final boolean autoReload;
    private final int autoReloadIntervalSeconds;
    private final boolean logToConsole;
    private final List<String> msgAliases;
    private final List<String> replyAliases;
    private final List<String> toggleAliases;
    private final List<String> ignoreAliases;
    private final List<String> spyAliases;
    private final List<String> adminAliases;

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
        this.gradientPermission = TomlLite.string(m, "colors.gradient-permission", "vwhisper.msg.gradient").trim();

        this.miniMessage = TomlLite.bool(m, "format.minimessage", false);
        this.formatSender = TomlLite.string(m, "format.sender", "&8[&7我 &8→ &7#target#&8]&r #message#");
        this.formatReceiver = TomlLite.string(m, "format.receiver", "&8[&7#sender# &8→ &7我&8]&r #message#");
        this.formatSpy = TomlLite.string(m, "format.spy", "&8[&cSpy&8] &7#sender# &8→ &7#target#&8:&r #message#");
        this.formatConsole = TomlLite.string(m, "format.console", "&8[&c控制台 &8→ &7我&8]&r #message#");

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
        this.soundName = TomlLite.string(m, "sound.name", "minecraft:entity.experience_orb.pickup").trim();
        this.soundVolume = (float) TomlLite.decimal(m, "sound.volume", 1.0D);
        this.soundPitch = (float) TomlLite.decimal(m, "sound.pitch", 1.0D);

        this.saveIgnores = TomlLite.bool(m, "storage.save-ignores", true);
        this.saveToggles = TomlLite.bool(m, "storage.save-toggles", true);

        this.autoReload = TomlLite.bool(m, "advanced.auto-reload", false);
        this.autoReloadIntervalSeconds =
                (int) Math.max(1L, TomlLite.integer(m, "advanced.auto-reload-interval-seconds", 3L));
        this.logToConsole = TomlLite.bool(m, "advanced.log-to-console", true);

        this.msgAliases = aliases(m, "commands.msg", "tell", "w", "m", "pm", "whisper");
        this.replyAliases = aliases(m, "commands.reply", "r");
        this.toggleAliases = aliases(m, "commands.msgtoggle", "togglemsg", "tmsg");
        this.ignoreAliases = aliases(m, "commands.ignore");
        this.spyAliases = aliases(m, "commands.spy", "socialspy", "msgspy");
        this.adminAliases = aliases(m, "commands.vwhisper", "VWhisper", "vws");
    }

    /** 别名：配了就用配置里的（可以写空数组=不给别名），没配才用默认值。 */
    private static List<String> aliases(final Map<String, Object> m, final String key, final String... fallback) {
        final Object value = m.get(key);
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            final List<String> list = new ArrayList<>((List<String>) value);
            return list;
        }
        return Arrays.asList(fallback);
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

    /** 用 jar 里自带的默认 config.toml 造一份配置 —— 连内置模板都读不出来时的兜底。 */
    public static Configuration defaults() {
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("config.toml")) {
            if (in == null) {
                throw new IOException("jar 里找不到默认 config.toml");
            }
            return new Configuration(TomlLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException("连默认配置都读不出来：" + e, e);
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

    public String colorMode() {
        return colorMode;
    }

    public String gradientPermission() {
        return gradientPermission;
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
        for (int i = 0; i + 1 < args.length; i += 2) {
            text = text.replace("#" + args[i] + "#", String.valueOf(args[i + 1]));
        }
        return prefix + text;
    }

    /** 不带 prefix 的原始提示语 —— 少数场景（比如要拼换行）用。 */
    public String rawMessage(final String key) {
        final String text = messages.get(key);
        return text == null ? key : text;
    }

    public long cooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean soundEnabled() {
        return soundEnabled;
    }

    public String soundName() {
        return soundName;
    }

    public float soundVolume() {
        return soundVolume;
    }

    public float soundPitch() {
        return soundPitch;
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

    public List<String> msgAliases() {
        return msgAliases;
    }

    public List<String> replyAliases() {
        return replyAliases;
    }

    public List<String> toggleAliases() {
        return toggleAliases;
    }

    public List<String> ignoreAliases() {
        return ignoreAliases;
    }

    public List<String> spyAliases() {
        return spyAliases;
    }

    public List<String> adminAliases() {
        return adminAliases;
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

package cn.shijiu.vwhisper;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 私聊的核心：查人、各种拦路闸门，以及三种身份看到的三份渲染。
 *
 * <p>跨服这件事本身没什么魔法 —— 代理对着 {@code target.sendMessage()} 直接发，
 * 后端服务器压根不知道有这条消息，人在哪个服都收得到。真正的活儿都在「谁该看、谁不该看」上。
 */
public final class WhisperService {

    private final VWhisper plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Store store;
    /** 上一次发消息的时刻，用来算冷却。 */
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();
    /** 组件反序列化失败只报一次，别每条私聊都刷屏。 */
    private boolean warnedParse;

    public WhisperService(final VWhisper plugin, final ProxyServer proxy, final Logger logger, final Store store) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.store = store;
    }

    // ------------------------------------------------------------------
    // 找人
    // ------------------------------------------------------------------

    /** 查玩家的结果：人是 null 的时候，ambiguous 用来区分「查不到」和「好几个人都对得上」。 */
    public static final class Lookup {
        private final Player player;
        private final boolean ambiguous;

        Lookup(final Player player, final boolean ambiguous) {
            this.player = player;
            this.ambiguous = ambiguous;
        }

        public Player player() {
            return player;
        }

        public boolean ambiguous() {
            return ambiguous;
        }
    }

    /**
     * 先精确、再按名字前缀补；有多个前缀匹配就报「有歧义」，让玩家多打几个字。
     * ⚠️ 没用 {@code ProxyServer#matchPlayer}：自己算清楚，免得 Velocity 换版本改了行为。
     */
    public Lookup findPlayer(final String name) {
        final Optional<Player> exact = proxy.getPlayer(name);
        if (exact.isPresent()) {
            return new Lookup(exact.get(), false);
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        Player found = null;
        int matches = 0;
        for (final Player candidate : proxy.getAllPlayers()) {
            if (candidate.getUsername().equalsIgnoreCase(name)) {
                return new Lookup(candidate, false);
            }
            if (candidate.getUsername().toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches++;
                found = candidate;
            }
        }
        return new Lookup(matches == 1 ? found : null, matches > 1);
    }

    // ------------------------------------------------------------------
    // 发一条私聊
    // ------------------------------------------------------------------

    /**
     * 从 source 给 targetName 发一条私聊。
     *
     * @return 发出去了没有；失败的原因已经回给发送者
     */
    public boolean send(final CommandSource source, final String targetName, final String rawMessage) {
        final Configuration config = plugin.configuration();
        final Player sender = source instanceof Player ? (Player) source : null;

        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            plugin.send(source, config.message("usage-msg"));
            return false;
        }
        // ① 自己这边这个服能不能用私聊（控制台没有"所在服"，默认放行）
        if (!config.filter().allows(serverName(sender))) {
            plugin.send(source, config.message("server-denied"));
            return false;
        }
        // ② 找人
        final Lookup lookup = findPlayer(targetName);
        if (lookup.player() == null) {
            plugin.send(source, config.message(lookup.ambiguous() ? "ambiguous-target" : "player-not-found",
                    "target", targetName));
            return false;
        }
        final Player target = lookup.player();
        // ③ 给自己发？算了
        if (sender != null && target.getUniqueId().equals(sender.getUniqueId())) {
            plugin.send(source, config.message("self-message"));
            return false;
        }
        // ④ 对方所在的服能不能收（比如起床服想安静一会儿）
        if (!config.filter().allows(serverName(target))) {
            plugin.send(source, config.message("server-denied"));
            return false;
        }
        // ⑤ 对方是不是把私聊关了
        if (!store.isReceiving(target.getUniqueId())
                && !Permissions.has(source, Permissions.TOGGLE_BYPASS, false)) {
            plugin.send(source, config.message("target-off", "target", target.getUsername()));
            return false;
        }
        // ⑥ 对方是不是屏蔽了我
        if (sender != null
                && store.isIgnoring(target.getUniqueId(), sender.getUniqueId())
                && !Permissions.has(source, Permissions.IGNORE_BYPASS, false)) {
            plugin.send(source, config.message("target-ignored", "target", target.getUsername()));
            return false;
        }
        // ⑦ 冷却
        if (sender != null && !checkCooldown(source, sender, config)) {
            return false;
        }
        deliver(source, sender, target, rawMessage);
        return true;
    }

    private boolean checkCooldown(final CommandSource source, final Player sender, final Configuration config) {
        final long seconds = config.cooldownSeconds();
        if (seconds <= 0 || Permissions.has(source, Permissions.COOLDOWN_BYPASS, false)) {
            lastSent.put(sender.getUniqueId(), System.currentTimeMillis());
            return true;
        }
        final Long last = lastSent.get(sender.getUniqueId());
        if (last == null) {
            lastSent.put(sender.getUniqueId(), System.currentTimeMillis());
            return true;
        }
        final long elapsed = (System.currentTimeMillis() - last) / 1000L;
        if (elapsed >= seconds) {
            lastSent.put(sender.getUniqueId(), System.currentTimeMillis());
            return true;
        }
        plugin.send(source, config.message("cooldown", "seconds", seconds - elapsed));
        return false;
    }

    /** 一切检查都过了，真的发。 */
    private void deliver(final CommandSource source, final Player sender, final Player target, final String raw) {
        final Configuration config = plugin.configuration();
        final String mode = Permissions.has(source, Permissions.MSG_COLOR, false)
                ? "parse" : config.colorMode();
        final String gradientPermission = config.gradientPermission();
        final boolean gradientAllowed = gradientPermission.isEmpty()
                || Permissions.has(source, gradientPermission, false);
        final Component message = ChatColors.component(mode, raw, gradientAllowed);

        final Map<String, String> placeholders = new LinkedHashMap<>();
        final String senderName = sender == null ? ConsoleLabel.NAME : sender.getUsername();
        placeholders.put("#sender#", senderName);
        placeholders.put("#target#", target.getUsername());
        placeholders.put("#sender-server#", sender == null ? ConsoleLabel.SERVER : serverName(sender));
        placeholders.put("#target-server#", serverName(target));

        final Component toSender = render(config, config.formatSender(), placeholders, message);
        final Component toTarget = render(config, sender == null ? config.formatConsole() : config.formatReceiver(),
                placeholders, message);

        if (sender != null) {
            sender.sendMessage(toSender);
        }
        target.sendMessage(toTarget);

        if (config.soundEnabled()) {
            target.playSound(Sound.sound(Key.key(config.soundName()), Sound.Source.PLAYER,
                    config.soundVolume(), config.soundPitch()));
        }

        // /reply 记忆：双方都记，谁都能接着 /r
        if (sender != null) {
            store.rememberContact(sender.getUniqueId(), target.getUniqueId());
        }

        // 窥屏：把消息转给所有开着 spy 的人，双方不在排除名单才行
        spyCast(sender, target, config, placeholders, message);

        if (config.logToConsole()) {
            logger.info("[vwhisper] " + senderName + " -> " + target.getUsername() + ": "
                    + PlainComponentSerializer.plain().serialize(message));
        }
    }

    private void spyCast(final Player sender, final Player target, final Configuration config,
                         final Map<String, String> placeholders, final Component message) {
        if (Permissions.has(sender, Permissions.SPY_BYPASS, false)
                || Permissions.has(target, Permissions.SPY_BYPASS, false)) {
            return;
        }
        Component spy = null;
        for (final Player watcher : proxy.getAllPlayers()) {
            if (sender != null && watcher.getUniqueId().equals(sender.getUniqueId())) {
                continue;
            }
            if (watcher.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (!store.isSpying(watcher.getUniqueId())
                    || !Permissions.has(watcher, Permissions.SPY, false)) {
                continue;
            }
            if (spy == null) {
                spy = render(config, config.formatSpy(), placeholders, message);
            }
            watcher.sendMessage(spy);
        }
    }

    // ------------------------------------------------------------------
    // 渲染：占位符 -> 解析颜色 -> 插 message 组件
    // ------------------------------------------------------------------

    /**
     * 渲染一份模板。
     *
     * @param message 已经处理好的消息组件 —— 它不参与颜色解析，避免玩家自己写的东西被当成语法
     */
    private Component render(final Configuration config, final String template,
                             final Map<String, String> placeholders, final Component message) {
        String resolved = template;
        for (final Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace(entry.getKey(), entry.getValue());
        }
        if (!resolved.contains("#message#")) {
            // 模板忘了写 #message#：把内容接在后面，总比把话吞掉强
            resolved = resolved + " #message#";
        }
        final String[] parts = resolved.split("#message#", -1);
        final TextComponent.Builder builder = Component.text();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(message);
            }
            builder.append(parse(config, parts[i]));
        }
        return builder.build();
    }

    private Component parse(final Configuration config, final String text) {
        try {
            if (config.miniMessage()) {
                return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(text);
            }
            return ChatColors.SERIALIZER.deserialize(text);
        } catch (final Throwable t) {
            if (!warnedParse) {
                logger.warn("[vwhisper] 格式串解析失败，已按纯文本发送: " + t);
                warnedParse = true;
            }
            return Component.text(text);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    static String serverName(final Player player) {
        if (player == null) {
            return null;
        }
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("-");
    }

    /** 控制台发出来的私聊，占位符里显示什么。 */
    public static final class ConsoleLabel {
        public static final String NAME = "控制台";
        public static final String SERVER = "console";

        private ConsoleLabel() {
        }
    }
}

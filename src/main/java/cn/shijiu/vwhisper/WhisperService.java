package cn.shijiu.vwhisper;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 私聊的核心：查人、各种拦路闸门，以及三种身份看到的三份渲染。
 *
 * <p>跨服这件事本身没什么魔法 —— 代理对着 {@code target.sendMessage()} 直接发，
 * 后端服务器压根不知道有这条消息，人在哪个服都收得到。真正的活儿都在「谁该看、谁不该看」上。
 */
public final class WhisperService {

    /**
     * 样式探针：一个玩家打不出来的私用字符，用来问出 {@code #message#} 落点上的颜色。
     * 跟 {@link ChatColors} 里渐变用的哨兵（{@code U+E000}/{@code U+E001}）错开，互不干扰。
     */
    private static final char PROBE_CHAR = '\uE002';
    private static final String PROBE = String.valueOf(PROBE_CHAR);

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
        // ③ 给自己发？默认允许（当随身便签用），关掉才拦
        final boolean self = sender != null && target.getUniqueId().equals(sender.getUniqueId());
        if (self && !config.allowSelfMessage()) {
            plugin.send(source, config.message("self-message"));
            return false;
        }
        // ④ 对方所在的服能不能收（比如起床服想安静一会儿）
        if (!config.filter().allows(serverName(target))) {
            plugin.send(source, config.message("server-denied"));
            return false;
        }
        // ⑤ 对方是不是把私聊关了。
        //    自己跟自己说话没有「拒收」这一说，所以 self 时不查（否则关了接收就记事都记不了）
        if (!self
                && !store.isReceiving(target.getUniqueId())
                && !Permissions.has(source, Permissions.TOGGLE_BYPASS, false)) {
            plugin.send(source, config.message("target-off", "target", target.getUsername()));
            return false;
        }
        // ⑥ 对方是不是屏蔽了我（同上：自己对自己不算）
        if (!self
                && sender != null
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
        // 渐变不单独要权限：跟着 mode 走，跟 &c 一个待遇
        final Component message = ChatColors.component(mode, raw);

        final Map<String, String> placeholders = new LinkedHashMap<>();
        final String senderName = sender == null ? ConsoleLabel.NAME : sender.getUsername();
        placeholders.put("#sender#", senderName);
        placeholders.put("#target#", target.getUsername());
        placeholders.put("#sender-server#", sender == null ? ConsoleLabel.SERVER : serverName(sender));
        placeholders.put("#target-server#", serverName(target));

        final Component toSender = render(config, config.formatSender(), placeholders, message);
        final Component toTarget = render(config, sender == null ? config.formatConsole() : config.formatReceiver(),
                placeholders, message);

        // 自言自语：只发一条（用 sender 那套「我 → 我」），不然同一句话会收两遍
        final boolean self = sender != null && sender.getUniqueId().equals(target.getUniqueId());
        if (self) {
            sender.sendMessage(toSender);
        } else {
            if (sender != null) {
                sender.sendMessage(toSender);
            }
            target.sendMessage(toTarget);
        }

        if (config.soundEnabled()) {
            target.playSound(Sound.sound(Key.key(config.soundName()), Sound.Source.PLAYER,
                    config.soundVolume(), config.soundPitch()));
        }

        // /reply 记忆：双方都记，谁都能接着 /r。
        // ⚠️ 自言自语不记 —— 否则 /r 会指向自己，再也回不到上一个真正聊过的人
        if (sender != null && !self) {
            store.rememberContact(sender.getUniqueId(), target.getUniqueId());
        }

        // 窥屏：转给所有开着 spy 的人。自己跟自己说话不广播
        if (!self) {
            spyCast(sender, target, config, placeholders, message);
        }

        if (config.logToConsole()) {
            logger.info("[vwhisper] " + senderName + " -> " + target.getUsername() + ": "
                    + PlainText.of(message));
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
                // 模板里写在 #message# 前面的那个颜色码（&7 #message#）只染到了它自己那几个字符，
                // 消息是另一个兄弟节点，继承不到。所以把那个样式「带」给消息 —— 见 carry()。
                builder.append(carry(message, styleAt(config, parts[i - 1])));
            }
            builder.append(parse(config, parts[i]));
        }
        return builder.build();
    }

    /**
     * 把 style 当成消息的「底色」：包一层空文本的父组件。
     *
     * <p>走父组件而不是直接改消息本身，是为了让消息里玩家自己写的颜色照旧生效 ——
     * 子组件上显式设了的值会盖掉父组件传下来的，没设的才继承。
     *
     * @param style {@link #styleAt} 探到的样式；null 或空样式表示「没什么可带」，原样返回
     */
    private static Component carry(final Component message, final Style style) {
        if (style == null) {
            return message;
        }
        final TextComponent.Builder wrapper = Component.text();
        boolean any = false;
        if (style.color() != null) {
            wrapper.color(style.color());
            any = true;
        }
        if (style.font() != null) {
            wrapper.font(style.font());
            any = true;
        }
        for (final TextDecoration decoration : TextDecoration.values()) {
            final TextDecoration.State state = style.decoration(decoration);
            if (state != TextDecoration.State.NOT_SET) {
                wrapper.decoration(decoration, state);
                any = true;
            }
        }
        return any ? wrapper.append(message).build() : message;
    }

    /**
     * 问出「这段模板结尾处生效的是什么样式」。
     *
     * <p>做法是在尾巴上接一个探针字符再解析，然后找到含探针的那个节点读它的样式。
     * 为什么要这么绕：颜色码只作用于它后面的字符，模板最后一个颜色码后面如果是空的
     * （比如写 {@code &7#message#}），解析出来压根没有那个节点，光看结果问不出来。
     * 探针保证一定有个落点，而且它落在 #message# 的位置上，拿到的就是消息该有的样式。
     *
     * @return 结尾处的样式；探针没找到（理论上不会）就返回 null
     */
    private Style styleAt(final Configuration config, final String text) {
        final Component parsed = parse(config, text + PROBE);
        final Style[] found = new Style[1];
        walk(parsed, component -> {
            if (found[0] == null && component instanceof TextComponent
                    && ((TextComponent) component).content().indexOf(PROBE_CHAR) >= 0) {
                found[0] = component.style();
            }
        });
        return found[0];
    }

    /** 深一层层往下走，每个节点都交给 visitor —— 比递归好写，也不用担心环。 */
    private static void walk(final Component root, final Consumer<Component> visitor) {
        final Deque<Component> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final Component current = stack.pop();
            visitor.accept(current);
            final List<Component> children = current.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
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

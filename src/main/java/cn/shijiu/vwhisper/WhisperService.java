package cn.shijiu.vwhisper;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.sound.Sound;
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
import java.util.concurrent.atomic.AtomicLong;
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
    private volatile boolean warnedParse;
    /** 提示音放不出来（多半是 sound.name 写歪了）也只报一次。 */
    private volatile boolean warnedSound;

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
        deliver(config, source, sender, target, rawMessage);
        return true;
    }

    private boolean checkCooldown(final CommandSource source, final Player sender, final Configuration config) {
        final long seconds = config.cooldownSeconds();
        if (seconds <= 0 || Permissions.has(source, Permissions.COOLDOWN_BYPASS, false)) {
            lastSent.put(sender.getUniqueId(), System.currentTimeMillis());
            return true;
        }
        // ⚠️ 「看一眼旧的 + 再写新的」必须是原子的一步：拆成 get / put 两步时，
        //    两条并发的 /msg 会同时判定「已经过了冷却」，冷却等于没配。
        final long now = System.currentTimeMillis();
        final AtomicLong remain = new AtomicLong();
        lastSent.compute(sender.getUniqueId(), (uuid, last) -> {
            if (last == null || now - last >= seconds * 1000L) {
                remain.set(0L);
                return now;
            }
            remain.set(seconds - (now - last) / 1000L);
            return last;
        });
        if (remain.get() > 0L) {
            plugin.send(source, config.message("cooldown", "seconds", remain.get()));
            return false;
        }
        return true;
    }

    /** 下线清掉这个人的冷却记录 —— 否则每个发过言的玩家都会在这里永久留一条。 */
    public void forget(final UUID uuid) {
        lastSent.remove(uuid);
    }

    /**
     * 一切检查都过了，真的发。
     *
     * @param config {@code send()} 阶段用过的**同一份**配置快照。以前这里重新读一次
     *               {@code plugin.configuration()}，于是一条消息会按「旧配置放行、
     *               新配置渲染」混着跑（reload 恰好插在中间时尤为明显）。
     */
    private void deliver(final Configuration config, final CommandSource source, final Player sender,
                         final Player target, final String raw) {
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

        // 「对方」= 看这份的人会想回给谁：发送者那份是收件人，收件人那份是发送者
        final Component toSender = render(config, config.formatSender(), placeholders, message,
                target.getUsername());
        final Component toTarget = render(config, sender == null ? config.formatConsole() : config.formatReceiver(),
                placeholders, message, senderName);

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

        // ⚠️ 提示音放在最后：以前它夹在中间，一旦音效 id 非法抛异常，
        //    后面的 reply 记忆 / 窥屏 / 控制台日志就整段不执行了（消息本体倒已经发出去）。
        //    现在 id 在起服就校验过了，这里只剩 playSound 本身的兜底。
        // 🔴 自言自语不响 —— 「提醒」这个语义只在【别人】发给你的时候成立
        if (!self) {
            playCue(config, config.soundTarget(), target);
            if (sender != null) {
                playCue(config, config.soundSender(), sender);
            }
        }

        // /reply 记忆：双方都记，谁都能接着 /r。
        // ⚠️ 自言自语不记 —— 否则 /r 会指向自己，再也回不到上一个真正聊过的人
        if (sender != null && !self) {
            store.rememberContact(sender.getUniqueId(), target.getUniqueId());
        }

        // 窥屏：转给所有开着 spy 的人。自己跟自己说话不广播
        if (!self) {
            spyCast(sender, target, config, placeholders, message, senderName);
        }

        if (config.logToConsole()) {
            logger.info("[vwhisper] " + senderName + " -> " + target.getUsername() + ": "
                    + PlainText.of(message));
        }
    }

    /**
     * 给某个人放一档提示音。
     *
     * <p>两个开关都要过：总闸 {@code [sound].enabled} 和这一档自己的 {@code enabled}。
     * 「叮」一声没响不值得搭上整条消息的后半段，所以异常照样吃掉、只报一次。
     */
    private void playCue(final Configuration config, final SoundCue cue, final Player player) {
        if (cue == null || player == null || !cue.enabled() || !config.soundEnabled()) {
            return;
        }
        // 还没真正进到某个后端服 —— Velocity 那边拿不到 emitter 实体 id，放了也是白放
        if (player.getCurrentServer().isEmpty()) {
            return;
        }
        try {
            // 🔴 必须调【带 Emitter】的这个重载。单参数的 playSound(Sound) 在 Velocity 里是
            //    空实现（adventure 的契约要求音效在玩家当前位置播放，代理没这个信息），
            //    调它不报错、不出声，从外面看就是"配置开了却没声音"。
            //    只有 playSound(Sound, Emitter) 被 ConnectedPlayer 真正实现并发出包。
            //    见 https://docs.papermc.io/velocity/dev/pitfalls/
            player.playSound(cue.sound(), Sound.Emitter.self());
        } catch (final Throwable ex) {
            if (!warnedSound) {
                logger.warn("[vwhisper] 提示音放不出来（检查 [sound] 里的 name 是不是合法的音效 id）：" + ex);
                warnedSound = true;
            }
        }
    }

    private void spyCast(final Player sender, final Player target, final Configuration config,
                         final Map<String, String> placeholders, final Component message,
                         final String senderName) {
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
                // 窥屏的人想回的是「谁发的」—— 所以这里的「对方」填发送者
                spy = render(config, config.formatSpy(), placeholders, message, senderName);
            }
            watcher.sendMessage(spy);
            playCue(config, config.soundSpy(), watcher);
        }
    }

    // ------------------------------------------------------------------
    // 渲染：占位符 -> 解析颜色 -> 插 message 组件
    // ------------------------------------------------------------------

    /**
     * 渲染一份模板。
     *
     * @param message   已经处理好的消息组件 —— 它不参与颜色解析，避免玩家自己写的东西被当成语法
     * @param otherName 「这条私聊的对方」：填悬停/点击里的 {@code {player}}。
     *                  看这份的人点一下要把 {@code /msg 对方} 填进聊天框 —— 所以每份都不一样
     */
    private Component render(final Configuration config, final String template,
                             final Map<String, String> placeholders, final Component message,
                             final String otherName) {
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
        // 复制到剪贴板用的是纯文本 —— 必须在插进模板【之前】从原始消息组件上取，
        // 那时它还没被 carry() 包一层，也没有任何模板里的东西混进来
        final String copyText = PlainText.of(message);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                // 模板里写在 #message# 前面的那个颜色码（&7 #message#）只染到了它自己那几个字符，
                // 消息是另一个兄弟节点，继承不到。所以把那个样式「带」给消息 —— 见 carry()。
                builder.append(ChatTooltip.copy(carry(message, styleAt(config, parts[i - 1])),
                        config, copyText));
            }
            builder.append(parse(config, parts[i]));
        }
        // 整条消息那一档最后挂：它挂在根上，正文自己设过事件就不会继承它
        return ChatTooltip.apply(builder.build(), config, placeholders, otherName);
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

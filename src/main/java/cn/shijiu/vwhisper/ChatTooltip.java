package cn.shijiu.vwhisper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 给私聊消息挂悬停提示和点击动作 —— 跟 Vmessage 那套是一个路子，两档挂载点：
 * <ol>
 *   <li><b>整条消息</b>（根组件）：悬停 = {@code prefix + hover}，点一下把 {@code suggest} 填进聊天框；
 *       {@link #apply(Component, Configuration, Map, String)}</li>
 *   <li><b>正文</b>（{@code #message#} 那一截）：悬停显示 {@code copy-hover}，点一下复制到剪贴板；
 *       {@link #copy(Component, Configuration, String)}</li>
 * </ol>
 *
 * <p>⚠️ 为什么第 ② 档必须<b>显式</b>挂事件：adventure 里子节点自己设过事件就不继承父的，
 *    反过来 —— 不设就会连根上的「发送时间 / 填 /msg」一起继承过去，那玩家鼠标放在正文上
 *    看到的还是发送时间、点一下也变成填命令，正是要避免的。所以正文哪怕提示留空也要挂一个
 *    空组件（空 = 不显示，但能挡住继承）。
 *
 * <p>🔴 正文是<b>逐节点</b>下钻着挂的，撞到已经有自己事件的整棵跳过。
 *    不靠「子节点会覆盖继承来的」这条规则兜底 —— 哪天继承行为变了、或者正文被多包了一层
 *    空节点（渐变染色就会这么干），靠继承就会漏。
 */
public final class ChatTooltip {

    /** 挂事件失败只报一次，别每条私聊都刷屏。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(true);

    /** 悬停提示前面那截固定前缀的默认值：先亮一个发送者所在服。留空 = 不加。 */
    static final String DEFAULT_PREFIX = "&8[&6#server#&8] ";
    /** 悬停提示的默认值。 */
    static final String DEFAULT_HOVER = "&e发送时间: &6{time}";
    /** 点一下填进聊天框的命令（末尾留一个空格，玩家接着就能输入内容）。 */
    static final String DEFAULT_SUGGEST = " /msg{player} ";
    /** 鼠标放在正文上时默认的提示。 */
    static final String DEFAULT_COPY_HOVER = "&7复制该文本";
    /** {time} 默认按 24 小时制的 时:分:秒 显示。 */
    static final String DEFAULT_TIME_PATTERN = "HH:mm:ss";
    /** 时间格式写错时的兜底。 */
    static final DateTimeFormatter FALLBACK_TIME = DateTimeFormatter.ofPattern(DEFAULT_TIME_PATTERN);

    private ChatTooltip() {
    }

    /**
     * 整条消息这一档当不当生效：总开关开着，且 prefix / hover / suggest 里至少有一个写了东西。
     *
     * <p>prefix 也算：只配了前缀时，提示里至少得看得见那一截。
     */
    static boolean enabled(final Configuration cfg) {
        return cfg != null && cfg.tooltipEnabled()
                && (hasText(cfg.tooltipPrefix()) || hasText(cfg.tooltipHover()) || hasText(cfg.tooltipSuggest()));
    }

    /** 正文「复制」这一档当不当生效：总开关 + copy 开关都开着。 */
    static boolean copyEnabled(final Configuration cfg) {
        return cfg != null && cfg.tooltipEnabled() && cfg.tooltipCopy();
    }

    /**
     * 给一条已经拼好的私聊组件挂上悬停提示和点击动作。
     *
     * @param placeholders 已经填好真值的占位符表（{@code #sender#} / {@code #target#} /
     *                     {@code #sender-server#} / {@code #target-server#}）；悬停串里能直接用
     * @param otherName    「这条私聊的对方」—— 填 {@code {player}}。看回执的发送者那边是收件人，
     *                     收件人那边是发送者，窥屏的人看到的是发送者
     * @return 挂好事件的组件；挂不上时原样返回（消息本身必须发出去）
     */
    static Component apply(final Component message, final Configuration cfg,
                           final Map<String, String> placeholders, final String otherName) {
        if (message == null || !enabled(cfg)) {
            return message;
        }
        // 前缀直接拼在 hover 前面：一起反序列化、一起填占位符，
        // 所以前缀里也能写 {time} / {player} / {server}（虽然 #server# 更常用）
        final String hover = nz(cfg.tooltipPrefix()) + nz(cfg.tooltipHover());
        final String suggest = cfg.tooltipSuggest();
        if (!hasText(hover) && !hasText(suggest)) {
            return message;
        }
        try {
            Component out = message;
            if (hasText(hover)) {
                out = out.hoverEvent(HoverEvent.showText(
                        deserialize(cfg, fill(hover, cfg, placeholders, otherName))));
            }
            if (hasText(suggest)) {
                out = out.clickEvent(ClickEvent.suggestCommand(
                        fill(suggest, cfg, placeholders, otherName)));
            }
            return out;
        } catch (final RuntimeException e) {
            // 组件不支持挂事件时退化成没有悬停/点击，也比整条私聊发不出去强
            warnOnce(e);
            return message;
        }
    }

    /**
     * 给**正文**（{@code #message#} 那一截）挂上「复制」。
     *
     * @param body 正文组件（已经做完颜色处理、也带上了模板里那个颜色）
     * @param text 点一下要复制到剪贴板的纯文本
     */
    static Component copy(final Component body, final Configuration cfg, final String text) {
        if (body == null || !copyEnabled(cfg) || text == null || text.isEmpty()) {
            return body;
        }
        // 提示留空也要 showText 一个空组件：空 = 什么都不显示，但能挡住父组件的悬停继承下来
        final String hover = cfg.tooltipCopyHover();
        final Component tip = hasText(hover) ? deserialize(cfg, hover) : Component.empty();
        try {
            return copyDeep(body, tip, text);
        } catch (final RuntimeException e) {
            // 组件树不支持改 children 时退回只挂根上：复制功能还在
            warnOnce(e);
            return body.hoverEvent(HoverEvent.showText(tip))
                    .clickEvent(ClickEvent.copyToClipboard(text));
        }
    }

    /**
     * 逐节点挂「复制」，**已经有自己事件的整棵子树直接跳过**。
     *
     * <p>为什么不是简单地在根上挂一次：根上挂一次要指望「子节点自带事件会覆盖继承来的」，
     * 而渐变染色会给正文包一层空节点，那层空根自己是没事件的，就可能被挂上复制。逐节点判一遍最稳。
     */
    private static Component copyDeep(final Component node, final Component tip, final String text) {
        // 自己带了 hover / click = 特殊片段：原样返回，一个字都不动
        if (node.hoverEvent() != null || node.clickEvent() != null) {
            return node;
        }
        Component out = node;
        final List<Component> children = node.children();
        if (!children.isEmpty()) {
            List<Component> replaced = null;
            for (int i = 0; i < children.size(); i++) {
                final Component old = children.get(i);
                final Component neu = copyDeep(old, tip, text);
                if (neu != old && replaced == null) {
                    replaced = new ArrayList<>(children);
                }
                if (replaced != null) {
                    replaced.set(i, neu);
                }
            }
            if (replaced != null) {
                out = node.children(replaced);
            }
        }
        return out.hoverEvent(HoverEvent.showText(tip))
                .clickEvent(ClickEvent.copyToClipboard(text));
    }

    /** 把占位符换成真值：配置里那四个 {@code #xxx#}，加上 {player} / {server} / {time}。 */
    private static String fill(final String text, final Configuration cfg,
                               final Map<String, String> placeholders, final String otherName) {
        String out = text;
        if (placeholders != null) {
            for (final Map.Entry<String, String> e : placeholders.entrySet()) {
                if (e.getValue() != null) {
                    out = out.replace(e.getKey(), e.getValue());
                }
            }
            // #server# / {server} 是「这条消息从哪个服来」的简写，等于 #sender-server#
            final String senderServer = placeholders.get("#sender-server#");
            if (senderServer != null) {
                out = out.replace("#server#", senderServer).replace("{server}", senderServer);
            }
        }
        if (otherName != null) {
            out = out.replace("{player}", otherName);
        }
        if (out.contains("{time}")) {
            out = out.replace("{time}", now(cfg));
        }
        return out;
    }

    /** 现在这一刻按配置里的格式/时区渲染出来的文本。 */
    private static String now(final Configuration cfg) {
        DateTimeFormatter format = cfg == null ? null : cfg.tooltipTimeFormat();
        if (format == null) {
            format = FALLBACK_TIME;
        }
        ZoneId zone = cfg == null ? null : cfg.tooltipZone();
        if (zone == null) {
            zone = ZoneId.systemDefault();
        }
        try {
            return ZonedDateTime.now(zone).format(format);
        } catch (final RuntimeException e) {
            // 格式串对某些时刻渲染不出来（极少见）：退回纯 时:分:秒，至少时间还在
            warnOnce(e);
            return ZonedDateTime.now(zone).format(FALLBACK_TIME);
        }
    }

    /**
     * 提示串 → 组件。跟着 {@code format.minimessage} 走：开着就用 MiniMessage 语法，
     * 关着就用 {@code &} 颜色码 —— 跟消息本体同一个待遇，免得两套写法混着来。
     */
    private static Component deserialize(final Configuration cfg, final String text) {
        if (cfg != null && cfg.miniMessage()) {
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(text);
        }
        return ChatColors.SERIALIZER.deserialize(text);
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    private static boolean hasText(final String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 时间格式写错时的兜底 —— Configuration 读配置时用。 */
    static DateTimeFormatter compileTimeFormat(final String pattern) {
        final String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) {
            return FALLBACK_TIME;
        }
        try {
            return DateTimeFormatter.ofPattern(p);
        } catch (final IllegalArgumentException e) {
            LoggerFactory.getLogger("vwhisper").warn(
                    "[vwhisper] Tooltip.time-format 写法有误（" + p + "），已按 HH:mm:ss 处理。"
                            + "参考 Java 的时间格式：yyyy-MM-dd HH:mm:ss");
            return FALLBACK_TIME;
        }
    }

    /** 时区名写错时的兜底 —— Configuration 读配置时用。 */
    static ZoneId parseZone(final String id) {
        if (id == null || id.trim().isEmpty()) {
            return ZoneId.systemDefault();
        }
        final String name = id.trim();
        try {
            return ZoneId.of(name);
        } catch (final RuntimeException e) {
            LoggerFactory.getLogger("vwhisper").warn(
                    "[vwhisper] Tooltip.time-zone 认不出这个时区（" + name + "），已按服务器系统时区处理。"
                            + "参考写法：Asia/Shanghai");
            return ZoneId.systemDefault();
        }
    }

    private static void warnOnce(final RuntimeException e) {
        if (WARNED.compareAndSet(true, false)) {
            LoggerFactory.getLogger("vwhisper").warn(
                    "[vwhisper] 给私聊消息挂悬停/点击事件失败，已按普通消息发出（之后不再重复提示）：" + e);
        }
    }
}

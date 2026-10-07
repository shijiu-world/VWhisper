package cn.shijiu.vwhisper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玩家在私聊内容里手写的那些颜色标记怎么处理。
 *
 * <p>后端（CMI / CMILib）认的写法比 {@code &4} 多得多，但私聊是代理直接发给客户端的，
 * 后端插件根本看不见这条消息 —— 那些语法到了代理端就变成一堆字面文本。这里把它们
 * 统一翻译成 adventure 的 legacy 写法再解析；legacy 表达不了的（比如 {@code {@字体}}）
 * 就摘掉，绝不把标记原样丢给玩家看。
 *
 * <p>支持的写法：
 * <ul>
 *   <li>{@code &0}-{@code &f}、{@code &k}-{@code &o}、{@code &r}（§ 同理）</li>
 *   <li>{@code &#RRGGBB}、{@code &#RGB}（3 位简写）</li>
 *   <li>{@code #RRGGBB}（前面不写 {@code &} 的裸 hex —— 玩家最常打出来的写法）</li>
 *   <li>{@code &x&F&F&0&0&0&0}（1.16 原生写法）</li>
 *   <li>{@code {#RRGGBB}}、{#RGB}（CMI 写法）</li>
 *   <li>{@code {@字体}} —— 摘掉（代理端没这能力）</li>
 *   <li>{@code {#FF0000>}渐变文字{#0000FF<}} —— 逐字插值染色</li>
 * </ul>
 *
 * <p>三种模式：{@code strip} 全摘掉、{@code parse} 解析、{@code keep} 原样当文本。
 */
public final class ChatColors {

    /** 用 {@code &} 当颜色符的 legacy 序列化器，开 hex 支持。 */
    public static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    /** 成对的渐变：{#A>}文字{#B<}（收尾也可能写成 {#B<>}） */
    private static final Pattern GRADIENT = Pattern.compile(
            "\\{#([^\\{\\}<>]+)>\\}(.*?)\\{#([^\\{\\}<>]+)<(>?)\\}");
    /** 落单的渐变起始标记：能认出颜色就退化成「用这颜色染后面」，认不出就摘掉 */
    private static final Pattern GRADIENT_START =
            Pattern.compile("\\{#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})>\\}|\\{#[^\\{\\}<>]*>\\}");
    /** 落单的渐变结束标记 */
    private static final Pattern GRADIENT_END = Pattern.compile("\\{#[^\\{\\}]*?<(>?)\\}");
    private static final Pattern FONT = Pattern.compile("\\{@[^\\{\\}]*\\}");
    private static final Pattern HEX_BRACE_6 = Pattern.compile("\\{#([0-9a-fA-F]{6})\\}");
    private static final Pattern HEX_BRACE_3 = Pattern.compile("\\{#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])\\}");
    private static final Pattern AMP_X = Pattern.compile("[&§]x(?:[&§][0-9a-fA-F]){6}");
    /** {@code &#RGB}：后面不能再跟 hex 字符，否则会误吃掉 {@code &#FF0000} 的前三位 */
    private static final Pattern AMP_HEX_3 = Pattern.compile("[&§]#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])(?![0-9a-fA-F])");
    /**
     * 裸 hex {@code #RRGGBB}（前面不带 {@code &}）—— 玩家最常打出来的写法，等价于 {@code &#RRGGBB}。
     *
     * <p>⚠️ 两边的断言都不能省：
     * <ul>
     *   <li>{@code (?<![&§{])} —— {@code &#FF0000} 里的 {@code #} 不能再补一个 {@code &}（会变成
     *       {@code &&#FF0000}，前面多出一个字面的 {@code &}）；{@code {#FF0000}} 是 CMI 的花括号写法，
     *       上面几步已经处理掉了，这里必须放过，不能把 {@code &#} 插进花括号里把写法搅坏。</li>
     *   <li>{@code (?![0-9a-fA-F])} —— {@code #FF0000AA}（8 位带 alpha）、{@code #1234567} 这类
     *       更长的串不是颜色码，整段留给纯文本，别吃掉前 6 位再漏一个尾巴。</li>
     * </ul>
     *
     * <p>⚠️ 只认 6 位，故意不支持裸的 {@code #RGB}：中文聊天里 {@code #666}「666」是高频网络用语，
     * 3 位裸 hex 的误伤率太高。带 {@code &} 的 {@code &#F00} 本来就不歧义，照旧支持。
     * 📌 与 Vmessage 的 {@code ChatColors} 同源，改语法两边都要改。
     */
    private static final Pattern BARE_HEX_6 =
            Pattern.compile("(?<![&§{])#([0-9a-fA-F]{6})(?![0-9a-fA-F])");

    /** strip 模式要摘掉的全部标记，按最长优先排列 */
    private static final Pattern STRIP = Pattern.compile(
            "[&§]x(?:[&§][0-9a-fA-F]){6}"                    // &x&F&F&0&0&0&0
                    + "|[&§]#[0-9a-fA-F]{6}"                  // &#RRGGBB
                    + "|[&§]#[0-9a-fA-F]{3}(?![0-9a-fA-F])"   // &#RGB
                    + "|[&§][0-9a-fA-Fk-oK-OrR]"              // &4 &l &r ...
                    + "|\\{#[^\\{\\}]*?[<>][>]?\\}"           // 渐变起始/结束
                    + "|\\{@[^\\{\\}]*\\}"                    // 字体
                    + "|\\{#[A-Za-z0-9_]*\\}"                 // {#RRGGBB} {#RGB}
                    // ⚠️ 裸 hex 一定放最后：前面那些带 & / {} 的写法必须先被吃掉，
                    //    否则 #FF0000 会被当成裸色先摘掉，留下一个孤零零的 & 或 {
                    + "|(?<![&§{])#[0-9a-fA-F]{6}(?![0-9a-fA-F])");

    private ChatColors() {
    }

    /** 摘掉所有颜色标记，只留文字（对应 CMI 的 Colors.CleanUp）。 */
    public static String strip(final String s) {
        return s == null ? "" : STRIP.matcher(s).replaceAll("");
    }

    /**
     * 按模式把私聊内容变成组件。渐变不单独 gate —— 跟着 mode 走，跟别的颜色码一个待遇。
     *
     * @param mode strip / parse / keep
     * @param raw  玩家打的原话
     */
    public static Component component(final String mode, final String raw) {
        final String text = raw == null ? "" : raw;
        if (!"parse".equals(mode)) {
            // keep：把 &c 原样显示出来；strip：把标记删掉
            return Component.text("strip".equals(mode) ? strip(text) : text);
        }
        // 渐变没法用一串 & 码表达：先抠出来留个哨兵，其余走 legacy，解析完再把哨兵换成逐字染好色的组件
        final List<Gradient> gradients = new ArrayList<>();
        final String holed = extractGradients(text, gradients);
        Component result = SERIALIZER.deserialize(normalize(holed));
        for (int i = 0; i < gradients.size(); i++) {
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(sentinel(i))
                    .replacement(gradients.get(i).render())
                    .build());
        }
        return result;
    }

    /** 把各种写法归一化成 legacy 串，只做字符串变换 —— 便于单独测试。 */
    public static String normalize(final String s) {
        if (s == null) {
            return "";
        }
        String out = s.replace('§', '&');
        out = replaceGradientStart(out);
        out = GRADIENT_END.matcher(out).replaceAll("");
        out = FONT.matcher(out).replaceAll("");
        out = HEX_BRACE_6.matcher(out).replaceAll("&#$1");
        out = HEX_BRACE_3.matcher(out).replaceAll("&#$1$1$2$2$3$3");
        out = replaceAmpX(out);
        out = AMP_HEX_3.matcher(out).replaceAll("&#$1$1$2$2$3$3");
        // ⚠️ 裸 hex 放最后一步：&#RRGGBB / {#RRGGBB} 这些写法都已经变成 &#RRGGBB 了，
        //    BARE_HEX_6 的 (?<![&§{]) 会放过它们，不会二次加 &
        out = BARE_HEX_6.matcher(out).replaceAll("&#$1");
        return out;
    }

    // ------------------------------------------------------------------
    // 渐变
    // ------------------------------------------------------------------

    /** 一段渐变：从 from 色到 to 色，覆盖 text 这几个字。 */
    private static final class Gradient {
        private final int from;
        private final int to;
        private final String text;

        Gradient(final int from, final int to, final String text) {
            this.from = from;
            this.to = to;
            this.text = text;
        }

        /** 逐字插值染色 —— legacy 串只能给一整段上一种颜色，只能这么干。 */
        Component render() {
            final TextComponent.Builder builder = Component.text();
            final int count = text.length();
            for (int i = 0; i < count; i++) {
                final float t = count == 1 ? 0F : (float) i / (count - 1);
                final int rgb = mix(from, to, t);
                builder.append(Component.text(String.valueOf(text.charAt(i)))
                        .color(TextColor.color(rgb)));
            }
            return builder.build();
        }
    }

    private static String extractGradients(final String s, final List<Gradient> out) {
        final Matcher matcher = GRADIENT.matcher(s);
        final StringBuffer builder = new StringBuffer();
        int index = 0;
        while (matcher.find()) {
            final String text = matcher.group(2);
            final Integer from = toRgb(matcher.group(1));
            final Integer to = toRgb(matcher.group(3));
            if (from == null || to == null || text.isEmpty()) {
                // 认不出来（比如写了个中文颜色名）：退化成纯文字，标记丢掉
                matcher.appendReplacement(builder, Matcher.quoteReplacement(text));
                continue;
            }
            out.add(new Gradient(from, to, text));
            matcher.appendReplacement(builder, Matcher.quoteReplacement(sentinel(index)));
            index++;
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    /** 哨兵：一段不会被玩家打出来的私用字符，解析完再换回渐变组件。 */
    private static String sentinel(final int index) {
        return "\uE000" + index + "\uE001";
    }

    private static String replaceGradientStart(final String s) {
        final Matcher matcher = GRADIENT_START.matcher(s);
        final StringBuffer builder = new StringBuffer();
        while (matcher.find()) {
            final Integer rgb = matcher.group(1) == null ? null : toRgb(matcher.group(1));
            matcher.appendReplacement(builder,
                    Matcher.quoteReplacement(rgb == null ? "" : "&#" + hex6(rgb)));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String replaceAmpX(final String s) {
        final Matcher matcher = AMP_X.matcher(s);
        final StringBuffer builder = new StringBuffer();
        while (matcher.find()) {
            final StringBuilder hex = new StringBuilder("&#");
            for (final char c : matcher.group().toCharArray()) {
                if (HEX_CHARS.indexOf(c) >= 0) {
                    hex.append(c);
                }
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(hex.toString()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static final String HEX_CHARS = "0123456789abcdefABCDEF";

    /** 把 6 位 / 3 位 hex 解析成 0xRRGGBB；认不出来返回 null。 */
    private static Integer toRgb(final String token) {
        final String t = token.trim();
        if (t.length() == 6) {
            try {
                return Integer.parseInt(t, 16);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        if (t.length() == 3) {
            try {
                return Integer.parseInt("" + t.charAt(0) + t.charAt(0)
                        + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2), 16);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String hex6(final int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }

    private static int mix(final int from, final int to, final float t) {
        final int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        final int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        final int b = lerp(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int lerp(final int from, final int to, final float t) {
        return from + Math.round((to - from) * t);
    }
}

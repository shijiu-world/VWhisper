package cn.shijiu.vwhisper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * 组件 → 纯文本（目前只给控制台日志用）。
 *
 * <p>⚠️ 别改回 {@code PlainComponentSerializer.plain()}。那个类在
 * {@code adventure-text-serializer-plain} 这个 artifact 里，而 **Velocity 4.x 的发布
 * jar 没有把它打进去** —— 编译期看得到（maven 的 {@code velocity-api} 依赖包含它），
 * 运行期 {@code PluginClassLoader} 找不到，一执行 /msg 就炸
 * {@code NoClassDefFoundError}。
 *
 * <p>插件的 classpath 只包含 Velocity 自己带的那些类，而 {@code adventure-api} 里
 * 能安全遍历组件的就两个：{@link Component#children()} 和 {@link TextComponent#content()}。
 * 递归走一遍就够 —— 本插件的消息都是自己构造的文本组件树，不会有
 * translatable / NBT 这类需要额外解析的类型。
 *
 * <p>📌 通用教训：给 Velocity 写插件，凡是 <em>编译能过</em> 不代表 <em>运行就有</em>。
 * 用到了边界上的类（各种 serializer、非常规 artifact）先去 {@code velocity-*.jar} 里
 * 确认它在不在，别信 IDE 的自动补全。
 */
public final class PlainText {

    private PlainText() {
    }

    /** 把组件树拼成一段纯文本；空组件返回空串。 */
    public static String of(final Component c) {
        if (c == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        append(sb, c);
        return sb.toString();
    }

    private static void append(final StringBuilder sb, final Component c) {
        if (c instanceof TextComponent) {
            sb.append(((TextComponent) c).content());
        }
        for (final Component child : c.children()) {
            append(sb, child);
        }
    }
}

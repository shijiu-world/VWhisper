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
 * 递归走一遍就够。除了 {@link TextComponent#content()}，还要顺手照顾
 * {@code TranslatableComponent} / {@code KeybindComponent} / {@code ScoreComponent} /
 * {@code SelectorComponent} —— 它们的文本挂在自身字段上、不在 {@code children()} 里，
 * 开了 {@code format.minimessage} 之后模板就可能产生这类节点。
 *
 * <p>（这几个类都在 adventure-api 里，Velocity 的运行 jar 带它，安全。）
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
        } else if (c instanceof net.kyori.adventure.text.TranslatableComponent) {
            // MiniMessage 模式下可能有这些节点：它们的文本在自身字段里，
            // 不在 children() 里，只遍历子节点会把它们整段丢掉。
            sb.append(((net.kyori.adventure.text.TranslatableComponent) c).key());
        } else if (c instanceof net.kyori.adventure.text.KeybindComponent) {
            sb.append(((net.kyori.adventure.text.KeybindComponent) c).keybind());
        } else if (c instanceof net.kyori.adventure.text.ScoreComponent) {
            sb.append(((net.kyori.adventure.text.ScoreComponent) c).name());
        } else if (c instanceof net.kyori.adventure.text.SelectorComponent) {
            sb.append(((net.kyori.adventure.text.SelectorComponent) c).pattern());
        }
        for (final Component child : c.children()) {
            append(sb, child);
        }
    }
}

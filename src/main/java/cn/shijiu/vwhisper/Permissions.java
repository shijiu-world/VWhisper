package cn.shijiu.vwhisper;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.permission.Tristate;

/**
 * 权限节点清单 + 判定。
 *
 * <p>分两类：
 * <ul>
 *   <li><b>基础节点</b>（msg / reply / toggle / ignore）：受配置
 *       {@code permissions.allow-by-default} 控制 —— 默认 true，也就是服上没配过权限的人
 *       也能正常用私聊（Velocity 对没配的权限是「未定义」，不是「允许」）。</li>
 *   <li><b>特权节点</b>（spy、reload、各种 bypass、上色）：必须显式给，
 *       allow-by-default 对它们无效。默认只有控制台和 OP 能用。</li>
 * </ul>
 *
 * <p>注：渐变没有单独节点 —— 能用颜色就能用渐变，省得再配一遍。
 * <pre>
 *   /lp group default permission set vwhisper.msg true
 *   /lp group admin permission set vwhisper.* true      # 通配符，全部放通
 * </pre>
 */
public final class Permissions {

    /** 发私聊 /msg。 */
    public static final String MSG = "vwhisper.msg";
    /** 消息内容里可以用颜色码（&c、&#FF0000、渐变）—— 渐变不单独设权限，跟着这一个走。 */
    public static final String MSG_COLOR = "vwhisper.msg.color";
    /** /reply 快速回复。 */
    public static final String REPLY = "vwhisper.reply";
    /** /spy 窥屏。 */
    public static final String SPY = "vwhisper.spy";
    /** 自己的私聊不让窥屏看到。 */
    public static final String SPY_BYPASS = "vwhisper.spy.bypass";
    /** /msgtoggle 自己开关接收。 */
    public static final String TOGGLE = "vwhisper.toggle";
    /** 能发给已经关掉私聊的人。 */
    public static final String TOGGLE_BYPASS = "vwhisper.toggle.bypass";
    /** /ignore 屏蔽某人。 */
    public static final String IGNORE = "vwhisper.ignore";
    /** 能发给把他屏蔽了的人。 */
    public static final String IGNORE_BYPASS = "vwhisper.ignore.bypass";
    /** 不受冷却限制。 */
    public static final String COOLDOWN_BYPASS = "vwhisper.cooldown.bypass";
    /** /vwhisper reload。 */
    public static final String RELOAD = "vwhisper.reload";

    private Permissions() {
    }

    /**
     * 判断有没有某个权限。
     *
     * @param allowByDefault 权限「没配过」的时候算不算有。特权节点一律传 false。
     */
    public static boolean has(final CommandSource source, final String node, final boolean allowByDefault) {
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (source == null) {
            // 没有命令来源（不该发生）按没有权限处理 —— 别把特权默认放出去
            return false;
        }
        final Tristate value = source.getPermissionValue(node);
        if (value == Tristate.TRUE) {
            return true;
        }
        if (value == Tristate.FALSE) {
            return false;
        }
        return allowByDefault;
    }

    /**
     * 有权限才往下走；没权限就给对方发一句「你没权限」并返回 false。
     *
     * @param allowByDefault 同上，基础节点传配置值、特权节点传 false
     */
    public static boolean require(final VWhisper plugin, final CommandSource source,
                                  final String node, final boolean allowByDefault) {
        if (has(source, node, allowByDefault)) {
            return true;
        }
        plugin.send(source, plugin.configuration().message("no-permission", "permission", node));
        return false;
    }
}

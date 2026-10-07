import cn.shijiu.vwhisper.Configuration;

import java.nio.file.Path;

/**
 * 打印【真实配置文件】里解析出来的值，用来验证测试服 / 线上配置没写错。
 *
 * <p>不是测试，是排障工具：换 jar、改完线上配置之后跑一下，不用起服就能确认
 * 「配置真的读进去了」。看的是 `[Tooltip]` 和 `[sound]` 这两段（外加三个 format 串）。
 */
public final class ConfigProbe {

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：ConfigProbe <配置目录 或 config.toml 路径>");
            return;
        }
        // load() 收的是【配置目录】，不是文件路径；Logger 只在失败时用
        Path dir = Path.of(args[0]);
        if (java.nio.file.Files.isRegularFile(dir)) {
            dir = dir.getParent();
        }
        final Configuration.LoadResult result =
                Configuration.load(dir, org.slf4j.LoggerFactory.getLogger("probe"));
        if (result.error() != null) {
            System.out.println("❌ 解析错误：" + result.error());
            return;
        }
        final Configuration c = result.config();
        System.out.println("解析错误：无");
        System.out.println("---- [Tooltip] ----");
        System.out.println("  enabled    = " + c.tooltipEnabled());
        System.out.println("  prefix     = [" + c.tooltipPrefix() + "]");
        System.out.println("  hover      = [" + c.tooltipHover() + "]");
        System.out.println("  suggest    = [" + c.tooltipSuggest() + "]");
        System.out.println("  copy       = " + c.tooltipCopy());
        System.out.println("  copy-hover = [" + c.tooltipCopyHover() + "]");
        System.out.println("  time-zone  = " + c.tooltipZone());
        System.out.println("---- [sound] ----");
        System.out.println("  总闸       = " + (c.soundEnabled() ? "开" : "关（三档都不响）"));
        System.out.println("  收到私聊的人 = " + c.soundTarget().describe());
        System.out.println("  发出私聊的人 = " + c.soundSender().describe());
        System.out.println("  窥屏的人     = " + c.soundSpy().describe());
        System.out.println("---- [format] ----");
        System.out.println("  sender   = [" + c.formatSender() + "]");
        System.out.println("  receiver = [" + c.formatReceiver() + "]");
        System.out.println("  spy      = [" + c.formatSpy() + "]");
    }
}

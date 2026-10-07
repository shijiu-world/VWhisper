import cn.shijiu.vwhisper.Configuration;

import java.nio.file.Path;

/** 打印【真实配置文件】里 [Tooltip] 解析出来的值，用来验证测试服 / 线上配置没写错。 */
public final class TooltipProbe {

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法：TooltipProbe <配置目录 或 config.toml 路径>");
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
        System.out.println("Tooltip.enabled    = " + c.tooltipEnabled());
        System.out.println("Tooltip.prefix     = [" + c.tooltipPrefix() + "]");
        System.out.println("Tooltip.hover      = [" + c.tooltipHover() + "]");
        System.out.println("Tooltip.suggest    = [" + c.tooltipSuggest() + "]");
        System.out.println("Tooltip.copy       = " + c.tooltipCopy());
        System.out.println("Tooltip.copy-hover = [" + c.tooltipCopyHover() + "]");
        System.out.println("Tooltip.time-zone  = " + c.tooltipZone());
        System.out.println("format.sender      = [" + c.formatSender() + "]");
        System.out.println("format.receiver    = [" + c.formatReceiver() + "]");
        System.out.println("format.spy         = [" + c.formatSpy() + "]");
    }
}

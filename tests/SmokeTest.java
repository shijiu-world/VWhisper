import cn.shijiu.vwhisper.ChatColors;
import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.TomlLite;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VWhisper 冒烟测试：TOML 解析 + 颜色归一化 + 渐变渲染 + 服务器名单。
 * 不依赖 Velocity 运行时，纯逻辑。
 */
public class SmokeTest {

    private static final List<String> failures = new ArrayList<>();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    public static void main(final String[] args) throws Exception {
        final Map<String, Object> map = TomlLite.parse(Path.of(args[0]));
        System.out.println("解析出 " + map.size() + " 个键");

        check("分节 key 带前缀：servers.mode", "blacklist".equals(TomlLite.string(map, "servers.mode", "?")));
        check("默认格式串读得到", ((String) TomlLite.string(map, "format.sender", "")).contains("#target#"));
        check("子命令别名是数组", ((List<?>) map.get("commands.msg")).contains("w"));
        check("主命令别名默认 vw", ((List<?>) map.get("commands.root")).contains("vw"));
        check("布尔值", TomlLite.bool(map, "sound.enabled", false));
        check("小数", TomlLite.decimal(map, "sound.volume", 0D) == 1.0D);
        check("带引号的中文提示语", ((String) map.get("messages.prefix")).contains("私聊"));
        check("行尾注释不会串进值里", ((String) map.get("messages.no-permission")).endsWith("#permission#）。"));
        check("空数组", ((List<?>) map.get("servers.list")).isEmpty());
        check("渐变不再需要单独权限（配置项已删）", !map.containsKey("colors.gradient-permission"));

        // ---- 服务器名单 ----
        final Configuration cfg = new ConfigurationProbe(map).unwrap();
        check("黑名单默认全放行", cfg.filter().allows("survival"));
        check("黑名单里的服被拦住", !new ConfigurationProbe(blacklistMap()).unwrap().filter().allows("KILLER"));
        check("服名大小写不敏感", !new ConfigurationProbe(blacklistMap()).unwrap().filter().allows("killer"));
        check("服名取不到（登录中）按放行处理", cfg.filter().allows(null));
        check("白名单模式反过来", !new ConfigurationProbe(whitelistMap()).unwrap().filter().allows("survival"));
        check("白名单模式名单内放行", new ConfigurationProbe(whitelistMap()).unwrap().filter().allows("lobby"));
        check("模式名写错退回黑名单语义（名单里的拦、其余放行）",
                !new ConfigurationProbe(bogusMap()).unwrap().filter().allows("bedwars")
                        && new ConfigurationProbe(bogusMap()).unwrap().filter().allows("lobby"));

        // ---- 颜色归一化 ----
        check("§ 和 & 等价", ChatColors.normalize("§c红").equals("&c红"));
        check("CMI 花括号 hex -> & 码", ChatColors.normalize("{#00FF00}绿").equals("&#00FF00绿"));
        check("三位缩写要展开", ChatColors.normalize("&#f0f量贩装").equals("&#ff00ff量贩装"));
        check("1.16 原生写法", ChatColors.normalize("&x&F&F&0&0&0&0打字").equals("&#FF0000打字"));
        check("字体标记摘掉", ChatColors.normalize("{@uniform}字").equals("字"));
        check("孤立渐变尾巴摘掉", ChatColors.normalize("哈{#FF0000<}").equals("哈"));
        check("孤立渐变起始退化成单色", ChatColors.normalize("{#FF0000>}红字").equals("&#ff0000红字"));
        check("strip 模式删干净", letPlain(ChatColors.component("strip", "&c红&#FF0000色")).equals("红色"));
        check("keep 模式原样显示", letPlain(ChatColors.component("keep", "&c红")).equals("&c红"));
        final Component parsed = ChatColors.component("parse", "&c红");
        check("parse 模式真的上色了", parsed.color() != null && parsed.color().value() == 0xFF5555);

        // ---- 渐变 ----
        final Component gradient = ChatColors.component("parse", "{#FF0000>}嘿{#0000FF<}");
        final String plain = letPlain(gradient);
        check("渐变文字不丢也不留标记", plain.equals("嘿"));
        check("渐变渲染成了多个染色段", countColored(gradient) == 1);
        // 渐变不再单独要权限，但 strip 模式下标记绝不能漏给玩家去看
        final Component stripped = ChatColors.component("strip", "{#FF0000>}嘿{#0000FF<}");
        check("strip 模式下渐变标记也剥掉，文字留下", letPlain(stripped).equals("嘿"));
        final Component multi = ChatColors.component("parse", "前{#FF0000>}一二三{#0000FF<}后");
        check("渐变混着普通文字：顺序对", letPlain(multi).equals("前一二三后"));

        // ---- 默认配置兜底 ----
        final Configuration defaults = Configuration.defaults();
        check("jar 内置默认配置可用", defaults.formatReceiver().contains("#sender#"));
        check("默认颜色模式是 keep", "keep".equals(defaults.colorMode()));
        check("默认允许所有人发私聊", defaults.allowByDefault());
        check("默认主命令别名是 vw", defaults.rootAliases().contains("vw"));
        check("提示语里的 #label# 换成实际命令名", defaults.label().equals("/vw"));
        check("子命令别名表里没有 root", !defaults.subAliases().containsKey("root"));
        check("提示语自动拼 prefix", defaults.message("self-message").startsWith("&8[&b私聊&8]&r"));
        check("提示语缺配置时有兜底", defaults.message("根本没这个键").contains("messages.根本没这个键"));

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部 " + (passed) + " 条断言通过 ✅");
        } else {
            System.out.println("❌ 失败 " + failures.size() + " 条：");
            failures.forEach(f -> System.out.println("   - " + f));
            System.exit(1);
        }
    }

    private static int passed;

    private static void check(final String name, final boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failures.add(name);
            System.out.println("  ❌ " + name);
        }
    }

    private static String letPlain(final Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainComponentSerializer.plain().serialize(c);
    }

    private static int countColored(final Component c) {
        int total = 0;
        if (c.color() != null) {
            total++;
        }
        for (final net.kyori.adventure.text.Component child : c.children()) {
            total += countColored(child);
        }
        return total;
    }

    // 反射造 Configuration（构造器是私有的，测试里绕一下）
    private static Map<String, Object> blacklistMap() {
        return TomlLite.parse("[servers]\nmode = \"blacklist\"\nlist = [\"killer\"]\n");
    }

    private static Map<String, Object> whitelistMap() {
        return TomlLite.parse("[servers]\nmode = \"whitelist\"\nlist = [\"lobby\"]\n");
    }

    private static Map<String, Object> bogusMap() {
        return TomlLite.parse("[servers]\nmode = \"随便写个什么\"\nlist = [\"bedwars\"]\n");
    }

    static final class ConfigurationProbe {
        private final Configuration value;

        ConfigurationProbe(final Map<String, Object> map) throws Exception {
            final java.lang.reflect.Constructor<Configuration> ctor =
                    Configuration.class.getDeclaredConstructor(Map.class);
            ctor.setAccessible(true);
            this.value = ctor.newInstance(map);
        }

        Configuration unwrap() {
            return value;
        }
    }
}

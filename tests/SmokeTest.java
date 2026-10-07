import cn.shijiu.vwhisper.ChatColors;
import cn.shijiu.vwhisper.Configuration;
import cn.shijiu.vwhisper.PlainText;
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
        // ⚠️ v1.2.0 起 [sound] 拆成三档，volume / pitch 归到各档下面了
        check("小数", TomlLite.decimal(map, "sound.target.volume", 0D) == 1.0D);
        final String selfMsg = TomlLite.string(map, "messages.self-message", "");
        check("带引号的中文提示语", selfMsg.contains("不能给自己发私聊") && !selfMsg.startsWith("\""));
        final Map<String, Object> commentProbe = TomlLite.parse("tip = \"&c测试\" # 这是注释\n");
        check("行尾注释不会串进值里", "&c测试".equals(TomlLite.string(commentProbe, "tip", "")));
        // ⚠️ 没加引号的值 + 行尾注释：以前会把注释一起吞进值里，布尔被 parseBoolean 翻成 false、
        //    白名单匹配不上就 fail-open —— 全是静默的。这几条专门守着它们。
        final Map<String, Object> unquoted = TomlLite.parse(
                "enabled = true # 默认允许\nseconds = 5 # 秒\nmode = whitelist # 白名单\n");
        check("没加引号的布尔 + 行尾注释仍是 true", TomlLite.bool(unquoted, "enabled", false));
        check("没加引号的数字 + 行尾注释仍是整数", TomlLite.integer(unquoted, "seconds", 0L) == 5L);
        check("没加引号的字符串 + 行尾注释不带杂质",
                "whitelist".equals(TomlLite.string(unquoted, "mode", "?")));
        check("带引号的值里可以有 ## 和 #abcdef 颜色码",
                "&#FF0000红".equals(TomlLite.string(
                        TomlLite.parse("tip = \"&#FF0000红\" # 这是红色的\n"), "tip", "?")));
        // 多行数组：以前逐行扫描时中间几行没有 = 会被整段丢掉，名单直接变空
        final Map<String, Object> multiLine = TomlLite.parse(
                "list = [\n  \"bedwars\",\n  \"killer\",\n]\n");
        check("多行数组不再被整段丢掉", ((List<?>) multiLine.get("list")).size() == 2
                && ((List<?>) multiLine.get("list")).contains("bedwars"));
        // 段名行里带注释的 ]：以前用 lastIndexOf(']') 会抓到注释里那个
        final Map<String, Object> bracket = TomlLite.parse(
                "[messages]  # 提示语 [重要]\nself-message = \"&c不能给自己发私聊\"\n");
        check("段名行注释里的 ] 不会污染前缀",
                "&c不能给自己发私聊".equals(TomlLite.string(bracket, "messages.self-message", "?")));
        // 字符串以转义的反斜杠结尾时，收尾引号要认得出来
        final Map<String, Object> esc = TomlLite.parse("path = \"a\\\\b\"\n");
        check("转义的反斜杠：引号能正常闭合", "a\\b".equals(TomlLite.string(esc, "path", "?")));
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
        // ---- 裸 hex（#RRGGBB，前面不带 &）----
        check("裸 hex -> & 码", ChatColors.normalize("#FF0000红").equals("&#FF0000红"));
        check("裸 hex 小写也认", ChatColors.normalize("#ff0000红").equals("&#ff0000红"));
        check("&#FF0000 不被二次加 &", ChatColors.normalize("&#FF0000红").equals("&#FF0000红"));
        check("{#FF0000} 不被裸 hex 插一脚", ChatColors.normalize("{#FF0000}红").equals("&#FF0000红"));
        check("3 位裸 hex 不认（#666 是网络用语）",
                ChatColors.normalize("打得好 #666").equals("打得好 #666"));
        // 玩家真实写法：颜色码后面直接跟数字（早期版本的尾部断言把它挡掉了）
        check("裸 hex 后跟数字", ChatColors.normalize("#00ff001").equals("&#00ff001"));
        check("裸 hex 后跟字母", ChatColors.normalize("#FF0000abc").equals("&#FF0000abc"));
        check("strip 模式也摘掉裸 hex", letPlain(ChatColors.component("strip", "#FF0000红")).equals("红"));
        check("strip 不误伤 #666", letPlain(ChatColors.component("strip", "打得好 #666")).equals("打得好 #666"));
        check("strip 模式删干净", letPlain(ChatColors.component("strip", "&c红&#FF0000色")).equals("红色"));
        check("keep 模式原样显示", letPlain(ChatColors.component("keep", "&c红")).equals("&c红"));
        final Component parsed = ChatColors.component("parse", "&c红");
        check("parse 模式真的上色了", parsed.color() != null && parsed.color().value() == 0xFF5555);

        // ---- 格式串（format / hover / 提示文案）也认全套写法 ----
        // 以前只认 &c / &#RRGGBB，裸 hex 和 {#RRGGBB} 会原样显示给玩家
        check("格式串裸 hex 上色", firstColor(ChatColors.format("#FF0000红")) == 0xFF0000);
        check("格式串 CMI 花括号上色", firstColor(ChatColors.format("{#FF0000}红")) == 0xFF0000);
        check("格式串 &c 照旧上色", firstColor(ChatColors.format("&c红")) == 0xFF5555);
        check("格式串渐变不留标记", letPlain(ChatColors.format("{#FF0000>}嘿{#0000FF<}")).equals("嘿"));
        check("格式串始终 parse（不吃 colors 配置）", firstColor(ChatColors.format("#00ff00绿")) == 0x00FF00);

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
        check("默认颜色模式是 parse", "parse".equals(defaults.colorMode()));
        check("默认允许所有人发私聊", defaults.allowByDefault());
        check("默认主命令别名是 vw", defaults.rootAliases().contains("vw"));
        check("提示语里的 #label# 换成实际命令名", defaults.label().equals("/vw"));

        // ---- 顶层快捷命令 [shortcuts] ----
        check("shortcuts 解析成 命令名->子命令", "msg".equals(map.get("shortcuts.msg")));
        check("默认接管 /msg", defaults.shortcuts().containsKey("msg"));
        check("默认接管 /w", defaults.shortcuts().containsKey("w"));
        check("默认接管 /r（指向 reply）", "reply".equals(defaults.shortcuts().get("r")));
        check("快捷命令不和主命令别名撞名", !defaults.shortcuts().containsKey("vw"));
        check("走 /msg 进来时提示语显示 /msg", defaults.label("msg", "msg").equals("/msg"));
        check("走 /vw msg 进来时提示语显示 /vw msg", defaults.label("vw", "msg").equals("/vw msg"));
        check("拿不到 alias 时退回 /vw msg", defaults.label(null, "msg").equals("/vw msg"));
        check("用法提示里不再写死子命令名", defaults.rawMessage("usage-msg").equals("&7用法：&f#label# <玩家> <消息>"));
        check("子命令别名表里没有 root", !defaults.subAliases().containsKey("root"));
        check("prefix 留空时提示语原样返回", defaults.message("self-message").equals("&c不能给自己发私聊"));
        check("配了 prefix 就自动拼在前面",
                new ConfigurationProbe(prefixMap()).unwrap().message("self-message").startsWith("&8[&b私聊&8]&r"));
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
        return PlainText.of(c);
    }

    /** 组件树里第一个非空颜色（RGB），没上色返回 -1 */
    private static int firstColor(final net.kyori.adventure.text.Component c) {
        if (c.color() != null) {
            return c.color().value();
        }
        for (final net.kyori.adventure.text.Component child : c.children()) {
            final int v = firstColor(child);
            if (v >= 0) {
                return v;
            }
        }
        return -1;
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

    private static Map<String, Object> prefixMap() {
        return TomlLite.parse("[messages]\nprefix = \"&8[&b私聊&8]&r\"\nself-message = \"&c不能给自己发私聊\"\n");
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

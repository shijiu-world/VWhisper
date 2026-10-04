import cn.shijiu.vwhisper.PlainText;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * 运行环境校验：确认插件在【只有 Velocity 的那种 classpath】下不缺类。
 *
 * <p>起因是一次实机事故 —— {@code /msg} 一执行就炸：
 * <pre>
 * NoClassDefFoundError: net/kyori/adventure/text/serializer/plain/PlainComponentSerializer
 *   at cn.shijiu.vwhisper.WhisperService.deliver(WhisperService.java:211)
 * </pre>
 * 编译期毫无征兆：maven 的 {@code velocity-api} 依赖（provided）把这个类带了进来，
 * IDE 能补全、javac 能过；但 **Velocity 4.x 的发布 jar 里根本没有它** ——
 * {@code adventure-text-serializer-plain} 没被打进 proxy，插件的 classloader 自然找不到。
 *
 * <p>所以这里刻意用一个「只看得见 velocity jar + 本项目 classes」的隔离 ClassLoader
 * 去加载并调用 {@code PlainText}，模拟插件真实的分发环境。
 * 同时反向验一遍：同样的环境里旧的写法确实会挂 —— 证明这个校验测的确实是东西。
 *
 * <p>📌 用法：{@code java -cp <CP> ClasspathTest <velocity-*.jar>}
 */
public class ClasspathTest {

    private static int failures = 0;

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: ClasspathTest <velocity-*.jar>");
            System.exit(2);
        }
        final URL[] urls = {
                new java.io.File(args[0]).toURI().toURL(),
                new java.io.File("target/classes").toURI().toURL(),
        };
        // parent 用 platform loader：拿不到这条命令自己的 classpath，保证是纯净环境
        try (URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            final Class<?> componentCls = isolated.loadClass("net.kyori.adventure.text.Component");
            System.out.println("[环境] 隔离加载器只给了 velocity jar + target/classes");

            // ---- 1. 现在的写法：PlainText 在纯净环境里能用 ----
            final Method text = componentCls.getMethod("text", String.class);
            final Object parent = text.invoke(null, "你好 ");
            final Object child = text.invoke(null, "世界");
            final Method children = componentCls.getMethod("children", List.class);
            final Object tree = children.invoke(parent, List.of(child));

            final Class<?> plainText = isolated.loadClass(PlainText.class.getName());
            final Method of = plainText.getMethod("of", componentCls);
            final Object result = of.invoke(null, tree);
            check("PlainText 在真实运行环境里不缺类", "你好 世界".equals(result),
                    String.valueOf(result));

            check("空组件不炸", "".equals(of.invoke(null, new Object[]{null})), "");

            // ---- 2. 反证：旧的写法在同一环境里确实挂 ----
            boolean oldWayFailed = false;
            try {
                isolated.loadClass("net.kyori.adventure.text.serializer.plain.PlainComponentSerializer");
            } catch (final ClassNotFoundException expected) {
                oldWayFailed = true;
            }
            check("旧的 PlainComponentSerializer 在此环境里确实找不到（证明本校验有效）",
                    oldWayFailed, "");
        }

        System.out.println(failures == 0 ? "全部通过 ✅" : failures + " 条失败 ❌");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(final String name, final boolean ok, final String actual) {
        if (ok) {
            System.out.println("  ✅ " + name);
        } else {
            failures++;
            System.out.println("  ❌ " + name + " —— 实际：" + actual);
        }
    }
}

# VWhisper —— 跨服私聊（Velocity）

纯代理端插件，**子服一个插件都不用装**。消息是代理对着 `target.sendMessage()` 直接发出去的，
后端服务器根本不知道有这条消息，所以人在哪个服都收得到 —— 这就是"跨服"的全部原理。

## 为什么自己写

现成的是有的，但都不太对得上本服的情况：

| 方案 | 问题 |
| --- | --- |
| **VeloChats**（ilezzov-code） | 功能最全（自定义频道/私聊/窥屏/@/冷却），但作者是俄语圈，出问题沟通成本高；整套配置按俄语版文档写，改起来费劲 |
| **szymon-off/vMessage** | 它 deny 原始聊天包，强依赖 SignedVelocity（代理+每个子服都要装）。本服已经在用自编译的 feusalamander 版 Vmessage，两边同名还打架 |
| **PumpkinMsg / NoobMSG** | 小众、跟随性一般；PumpkinMsg 还是 Velocity/Paper 双平台单 jar，行为不好预期 |
| **BMSProxyCore / NexusProxy** | 大而全的一体化核心，装上去等于给代理换了套框架，跟现有 LuckPerms + TAB + Vmessage 重叠太多 |
| **YinwuChat** | 要代理和**每个子服都装**插件，killer 服根本没装 PAPI，直接劝退 |

所以自己写了个只用 Velocity API、其余全走软依赖的版本：逻辑清楚、出问题能自己改。

## 安装

1. `vwhisper-1.0.1.jar` 丢进代理的 `plugins/` 目录
2. 启动代理一次，会自动生成 `plugins/vwhisper/config.toml`
3. 改完配置用 `/vw reload`（控制台直接敲也行）

## 命令

整个插件只有一个命令入口：**`/vwhisper`**，默认别名 **`/vw`**。剩下全是它的子命令
（嫌 `/vw msg` 太长？默认还把 `/msg`、`/w`、`/r` 这些短命令直接接管了，见下一节）：

| 命令 | 子命令别名 | 说明 |
| --- | --- | --- |
| `/vw msg <玩家> <消息>` | `m` `pm` `tell` `w` `whisper` | 发私聊（跨服，人在哪个服都收得到） |
| `/vw reply <消息>` | `r` | 回复最近跟自己聊过的人（两边都能接着回） |
| `/vw toggle [on\|off]` | `msgtoggle` `togglemsg` `tmsg` | 开关接收私聊 |
| `/vw ignore <玩家>` | — | 屏蔽 / 取消屏蔽 |
| `/vw spy [on\|off]` | `socialspy` `msgspy` | 私聊窥屏 |
| `/vw reload` | `rl` | 重载配置 |
| `/vw version` | `info` `ver` | 显示版本 |
| `/vw help` | `?` | 看这个列表（不带参数敲 `/vw` 也一样） |

主命令的别名和每个子命令的别名都在 `config.toml` 的 `[commands]` 里改：

```toml
[commands]
root = ["vw"]                       # 想叫别的就改这里；写 [] 就只用 /vwhisper
msg = ["m", "pm", "tell", "w", "whisper"]
```

⚠️ 主命令名固定小写：Velocity 底层 Brigadier 的 literal 节点大小写敏感，
注册成大写会导致敲小写时报"命令不存在"。

### 顶层快捷命令：接管 `/msg`、`/w`、`/r`

嫌 `/vw msg` 太长？默认就把这几个短命令注册成**顶层命令**，直接盖掉后端子服自己的：

```toml
[shortcuts]
msg = "msg"        # /msg <玩家> <消息>
w = "msg"          # /w …
m = "msg"
tell = "msg"
whisper = "msg"
reply = "reply"    # /reply <消息>
r = "reply"
```

**原理**：代理上谁注册了命令谁说了算。Velocity 注册了 `/msg` 之后，玩家敲 `/msg`
就由代理处理，**根本不会往后端转发** —— 后端子服那个（CMI 的 `/msg`、`/reply`）
一个包都收不到。这是故意的：全服私聊统一走 VWhisper，顺带就跨服了。

要点：

- 子服里带前缀的写法（`/cmi msg`）不受影响，被接管的只是短命令。
- 不想接管哪个，把那一行删掉即可（删掉 `/w` 它就会落回后端 CMI）。
- ⚠️ **命令名在起服时注册，改这里要重启代理**（配置其它部分仍可 `/vw reload` 热更新）。
- 名字被别的插件先占了会注册失败，起服日志会 WARN 一行，插件其它功能不受影响。
- 用法提示跟着实际命令走：敲 `/msg` 参数不对会提示"/msg <玩家> <消息>"，
  敲 `/vw msg` 则提示"/vw msg <玩家> <消息>"。

## 权限节点

分两类：

**基础节点** —— 受 `permissions.allow-by-default` 控制（默认 `true`，也就是没配过权限的普通玩家也能用）：

| 权限 | 作用 |
| --- | --- |
| `vwhisper.msg` | 用 `/vw msg` |
| `vwhisper.reply` | 用 `/vw reply` |
| `vwhisper.toggle` | 用 `/vw toggle` |
| `vwhisper.ignore` | 用 `/vw ignore` |

> 没权限的人敲命令看到的也是提示（"你没权限"），不是"命令不存在"；
> 帮助列表和 Tab 补全里也只会显示他真的能用的子命令。

**特权节点** —— 必须显式给，`allow-by-default` 对它们无效：

| 权限 | 作用 |
| --- | --- |
| `vwhisper.spy` | 窥屏开关 |
| `vwhisper.reload` | 重载配置 |
| `vwhisper.msg.color` | 私聊内容里可以用颜色码（`&c`、`&#FF0000`、渐变）—— **渐变不单独设权限** |
| `vwhisper.spy.bypass` | 自己的私聊不让窥屏看到 |
| `vwhisper.toggle.bypass` | 能发给关了私聊的人 |
| `vwhisper.ignore.bypass` | 能发给把自己屏蔽了的人 |
| `vwhisper.cooldown.bypass` | 不受冷却限制 |

LuckPerms 里通常这么给：

```text
/lp group default permission set vwhisper.msg true      # allow-by-default=false 时才需要
/lp group admin permission set vwhisper.* true          # 通配符，全部放通
/lp group default permission set vwhisper.msg.color true # 想让 VIP 用彩色私聊
```

## 消息格式里的占位符

在 `config.toml` 的 `[format]` 里写（`minimessage = false` 时用 `&` 颜色码，true 时用 MiniMessage 语法）：

| 占位符 | 含义 |
| --- | --- |
| `#sender#` `#target#` | 发送者 / 接收者名字 |
| `#sender-server#` `#target-server#` | 所在服（控制台发出去时是 `console`） |
| `#message#` | 消息内容，**一定放最后** |

`[messages]` 里的提示语还能用 `#label#`，会换成实际的命令名（`/vw`）—— 改了别名不用逐条改文本。

### 消息内容显示成什么颜色

`#message#` 前面的那个颜色码会**带**给消息本身：

```toml
sender = "&8[&7我 &8→ &7#target#&8]&r&7 #message#"   # 消息是灰的
sender = "&8[&7我 &8→ &7#target#&8]&r #message#"     # 消息不带颜色 → 客户端默认白色
```

- 带过去的是「底色」：消息里玩家自己写的 `&c`、渐变照旧盖掉它，不会串色。
- 颜色码后面哪怕一个字都没有（`&7#message#`）也一样有效 —— 不用为了生效硬塞个空格。
- 装饰（`&l` `&o`…）跟着一起带过去。
- 想让消息回到客户端默认色，就在 `#message#` 前留一个 `&r`（有空格也行），别再跟颜色码。

> 没有称号/前缀/`%xxx%` 之类的占位符：私聊是代理绕过子服直接发的，子服的 PlaceholderAPI
> 变量在代理端拿不到，硬要就要额外装 PAPIProxyBridge 并多一次跨服往返 —— 不值。
> 想让称号出现在聊天里是子服那边 `Chat.GeneralFormat` 的事，这里不掺和。

## 给自己发私聊（自言自语）

默认**允许** —— `/msg 自己的名字 记点什么` 就能给自己发，当随身便签用（记坐标、记待办）。

开关在 `config.toml`：

```toml
[general]
allow-self-message = true    # false = 敲 /msg 自己会提示 messages.self-message
```

允许时的几条规矩：

| 情况 | 处理 |
| --- | --- |
| 收到几条 | **只发一条**（用 `[format].sender` 那套「我 → 我」），不会同一句话收两遍 |
| 关了接收 / 屏蔽 | 对自己**不算数** —— 自己跟自己说话没有「拒收」这一说 |
| `/reply` | 不进记忆 —— 自言自语之后敲 `/r` 还是找上一个**真正聊过的人** |
| 窥屏 | 看不到（自己跟自己说话不广播） |
| 服务器名单、冷却 | 照旧生效 |

## 跟本服其它插件的关系

- **和 Vmessage 不冲突**：Vmessage 管公共聊天（跨服频道），VWhisper 管私聊，两者各管一段。
- **不依赖 PAPIProxyBridge**、也不需要在子服装任何东西：称号前缀这类变量走不了代理这条路，索性不做。
- **LuckPerms**：不是硬依赖，权限判定走 Velocity 原生的 `CommandSource#getPermissionValue`，
  谁提供权限都不影响（通常就是 LuckPerms）。
- **整套零第三方依赖**：jar 里不放任何库（本机 Maven 离线，也 shade 不进去），
  颜色（`&c` / `&#RRGGBB` / CMI 的花括号写法 / 渐变）是自己写的解析器，不引 PAPI、不引 mini。
  ⚠️ 但「零依赖」不等于不会 `NoClassDefFoundError` —— 见下面的血泪教训。

## 已知边界

- `/vw reply` 的记忆只在内存里，代理重启就清空；对方下线也回不了。
- 命令别名改了之后不会自动生效 —— 命令是在起服时注册的，改名要重启代理（配置本身可以 `/vw reload` 热更新）。
- 屏蔽名单和接收开关存在 `plugins/vwhisper/` 下的两个纯文本文件里（一行一条 UUID，肉眼可读）；
  **窥屏状态故意不落盘** —— 重启自动关，免得忘了关还在看别人的私聊。
- 冷却也是内存态，重启清零。
- **组件转纯文本（控制台日志）没用 `PlainComponentSerializer`** —— 那个类在 Velocity
  发布 jar 里不存在，用了就是 `NoClassDefFoundError`（详见 `tests/ClasspathTest.java`）。
  自己的 `PlainText.of()` 只用 `Component.children()` + `TextComponent.content()` 递归，
  都在 `adventure-api` 里、Velocity 一定有。

## 从源码构建

```bash
cd D:\Code\mc\plugins\VWhisper
JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B -o clean package
```

产物：`target/vwhisper-1.0.1.jar`（Java 17 / class 61，Velocity 3.4+ ~ 4.x 通用）。

> 构建为什么必须零依赖：本机 Maven 是离线的，装不上 maven-shade 插件，打不进第三方库，
> 所以连 TOML 解析都是自己写的（`TomlLite`，~300 行，格式写错也只是取到默认值，不会把插件搞挂）。

## 测试

三个测试都在仓库的 `tests/` 下，手动跑（没接 JUnit）。**建议用 PowerShell 跑** ——
Git Bash 会对 `-cp` 里的 `D:/...` 做路径转换，JVM 会莫名其妙找不到主类。

```powershell
cd D:\Code\mc\plugins\VWhisper
mvn -B -o -q package
$jdk = "D:\Code\Java\zulu25.34.17-ca-jdk25.0.3-win_x64\bin"
$cp  = "target\classes;D:\game\test_velocity\velocity\velocity-4.1.0-SNAPSHOT-16.jar"
$out = "D:\tmp\vwtest"
& "$jdk\javac.exe" -encoding UTF-8 -cp $cp -d $out tests\*.java

& "$jdk\java.exe" -cp "$cp;$out" SmokeTest   target\classes\config.toml
& "$jdk\java.exe" -cp "$cp;$out" ServiceTest
& "$jdk\java.exe" -cp "$cp;$out" ClasspathTest D:\game\test_velocity\velocity\velocity-4.1.0-SNAPSHOT-16.jar
```

| 测试 | 断言 | 覆盖 |
|---|---|---|
| `SmokeTest` | 49 | TOML 解析、颜色/渐变渲染（**要传 `target/classes/config.toml` 作 `args[0]`**） |
| `ServiceTest` | 51 | 动态代理桩掉 Velocity API，跑真实 `WhisperService`/`MsgCommand`：权限闸门、接收开关、屏蔽、窥屏、冷却、颜色权限、服务器名单、Tab 补全 |
| `ClasspathTest` | 3 | 运行环境校验，见下 |

> `ClasspathTest` 是 `/msg` 一次实机崩溃之后补的。它用一个**只看得见 velocity jar + 本项目 classes**
> 的隔离 ClassLoader 加载并调用工具类，同时反向验证旧的写法在同一环境里确实挂 ——
> 否则这测试就是在自欺欺人（跑得再绿也说明不了问题）。

## 血泪教训：编译能过 ≠ 运行就有

曾经在一次实机里，`/msg` 一执行就炸：

```
NoClassDefFoundError: net/kyori/adventure/text/serializer/plain/PlainComponentSerializer
  at cn.shijiu.vwhisper.WhisperService.deliver(WhisperService.java:211)
```

编译期毫无征兆：maven 的 `velocity-api`（provided）把这个类带了进来，IDE 能补全、javac 能过；
但 **Velocity 4.x 的发布 jar 里根本没有它** —— `adventure-text-serializer-plain`
这个 artifact 没被打进 proxy，插件的 classloader 自然找不到。

> 📌 **给 Velocity 写插件时**：凡是边界上的类（各种 serializer、非常规 artifact），
> 先去 `velocity-*.jar` 里确认它在不在，别信 IDE 的自动补全。
> 排查办法：把源码里所有 `import` 拎出来，逐个对照 jar 里的 `.class` 路径查一遍 ——
> 本项目就只有这一处中招（其余 22 个外部类都在）。

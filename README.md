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

1. `vwhisper-1.0.0.jar` 丢进代理的 `plugins/` 目录
2. 启动代理一次，会自动生成 `plugins/vwhisper/config.toml`
3. 改完配置用 `/vwhisper reload`（控制台直接敲也行）

## 命令

| 命令 | 别名 | 说明 |
| --- | --- | --- |
| `/msg <玩家> <消息>` | `tell` `w` `m` `pm` `whisper` | 发私聊 |
| `/reply <消息>` | `r` | 回复最近跟自己聊过的人（两边都能接着回） |
| `/msgtoggle [on\|off]` | `togglemsg` `tmsg` | 开关接收私聊 |
| `/ignore <玩家>` | — | 屏蔽 / 取消屏蔽 |
| `/spy [on\|off]` | `socialspy` `msgspy` | 私聊窥屏 |
| `/vwhisper <reload\|help\|version>` | `VWhisper` `vws` | 管理命令 |

别名都可以在 `config.toml` 的 `[commands]` 里改 —— 跟别的插件抢命令名时把那个别名删掉即可。
主命令名固定且小写（Velocity 底层 Brigadier 的 literal 节点大小写敏感，注册大写会导致敲小写时报"命令不存在"）。

## 权限节点

分两类：

**基础节点** —— 受 `permissions.allow-by-default` 控制（默认 `true`，也就是没配过权限的普通玩家也能用）：

| 权限 | 作用 |
| --- | --- |
| `vwhisper.msg` | 用 `/msg` |
| `vwhisper.reply` | 用 `/reply` |
| `vwhisper.toggle` | 用 `/msgtoggle` |
| `vwhisper.ignore` | 用 `/ignore` |

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

> 没有称号/前缀/`%xxx%` 之类的占位符：私聊是代理绕过子服直接发的，子服的 PlaceholderAPI
> 变量在代理端拿不到，硬要就要额外装 PAPIProxyBridge 并多一次跨服往返 —— 不值。
> 想让称号出现在聊天里是子服那边 `Chat.GeneralFormat` 的事，这里不掺和。

## 跟本服其它插件的关系

- **和 Vmessage 不冲突**：Vmessage 管公共聊天（跨服频道），VWhisper 管私聊，两者各管一段。
- **不依赖 PAPIProxyBridge**、也不需要在子服装任何东西：称号前缀这类变量走不了代理这条路，索性不做。
- **LuckPerms**：不是硬依赖，权限判定走 Velocity 原生的 `CommandSource#getPermissionValue`，
  谁提供权限都不影响（通常就是 LuckPerms）。
- **整套零第三方依赖**：jar 里除 Velocity 之外不引用任何库，所以不会出现 `NoClassDefFoundError`。
  颜色（`&c` / `&#RRGGBB` / CMI 的花括号写法 / 渐变）是自己写的解析器，不引 PAPI、不引 mini。

## 已知边界

- `/reply` 的记忆只在内存里，代理重启就清空；对方下线也回不了。
- 屏蔽名单和接收开关存在 `plugins/vwhisper/` 下的两个纯文本文件里（一行一条 UUID，肉眼可读）；
  **窥屏状态故意不落盘** —— 重启自动关，免得忘了关还在看别人的私聊。
- 冷却也是内存态，重启清零。

## 从源码构建

```bash
cd D:\Code\mc\plugins\VWhisper
JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B -o clean package
```

产物：`target/vwhisper-1.0.0.jar`（Java 17 / class 61，Velocity 3.4+ ~ 4.x 通用）。

> 构建为什么必须零依赖：本机 Maven 是离线的，装不上 maven-shade 插件，打不进第三方库，
> 所以连 TOML 解析都是自己写的（`TomlLite`，~300 行，格式写错也只是取到默认值，不会把插件搞挂）。

## 测试

两个测试都在仓库的 `tests/` 下，手动跑（没有接 JUnit，本机 Maven 是离线环境）：
- `ServiceTest`：用动态代理把 Velocity API 桩掉，跑真实的 `WhisperService` / `MsgCommand`，
  覆盖权限闸门、接收开关、屏蔽、窥屏、冷却、颜色权限、服务器名单、Tab 补全 —— 28 条断言
- 实机：本地 `D:\game\test_velocity`（Velocity 4.1.0-SNAPSHOT + LuckPerms + PAPIProxyBridge）起服通过，零异常

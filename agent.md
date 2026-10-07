# agent.md — VWhisper（跨服私聊）

> 给 AI 上手用的**代码地图**。服主视角的「怎么装、怎么配」在 `README.md`，本文档不重复。
> 本文档讲：代码在哪、改一处会牵动什么、哪些东西碰了就出事。

## 一句话定位

**纯代理端**插件，子服一个插件都不用装。消息是代理对着 `target.sendMessage()` 直接发出去的，
后端服务器根本不知道有这条消息 —— 这就是「跨服」的全部原理。

- 源码：`D:\Code\mc\plugins\VWhisper`
- 仓库：`git@github.com:shijiu-world/VWhisper.git`（**走 SSH**，https 会被本机代理掐断 502）
- 产物：`target/vwhisper-1.2.2.jar`（class 61，Velocity 3.4+ ~ 4.x 通用）
- 主命令 `/vwhisper`，默认别名 `/vw`；顶层快捷命令接管 `/msg` `/w` `/m` `/tell` `/whisper` `/reply` `/r`

---

## 源码地图

包 `cn.shijiu.vwhisper`，10 个主类 + 7 个命令类。

### 核心

| 文件 | 行 | 职责 | 备注 |
|---|---|---|---|
| `VWhisper.java` | 260 | 主类。注册命令/快捷命令、自动重载、起服打配置报告 | `registerShortcuts()` 把 `[shortcuts]` 注册成**顶层命令**；`onDisconnect` 清 reply 记忆 |
| `WhisperService.java` | 401 | **核心业务**：找人、闸门检查、投递、渲染 | `send()` 是主入口，`deliver()` 真正发消息；渲染的样式继承看下面「颜色继承」 |
| `Configuration.java` | 369 | 全部配置读取，`LoadResult` 携带错误原因 | 加载失败**保留旧配置** |
| `Store.java` | 223 | 内存态 + 落盘：屏蔽名单、接收开关、spy、reply 记忆 | 文件 `plugins/vwhisper/ignores.txt`、`toggles.txt` |
| `Permissions.java` | 89 | 权限闸门。`allow-by-default` 只影响 4 个基础节点 | 特权节点（spy/reload/bypass/color）一律要显式给 |
| `ChatColors.java` | 262 | 自研颜色解析器：`&c` / `&#RRGGBB` / 裸 `#RRGGBB` / `&x&F&F...` / `{#F00}` / 渐变 | 与 Vmessage 那份**同源但独立**，改语法两边都要改 |
| `ChatTooltip.java` | 265 | 悬停提示 + 点击动作（`[Tooltip]`）。两档：整条消息 = hover+suggest，正文 = 复制 | 思路对齐 Vmessage 的 `ChatTooltip`，但占位符是 VWhisper 那套 `#xxx#` |
| `SoundCue.java` | 137 | 一档提示音（`[sound]`）：enabled / name / volume / pitch / source | 🔴 音效 id 在**构造时**校验并预构造 `Sound`，不放进 `sound()` 现算 —— 见下面「提示音」 |
| `PlainText.java` | 47 | 组件转纯文本（控制台日志用） | 🔴 **不用** `PlainComponentSerializer`，见下 |
| `TomlLite.java` | 272 | 自研 TOML 解析（~300 行） | 为什么自研：离线 Maven 装不上任何库 |
| `command/RootCommand.java` | 213 | 唯一命令入口，子命令分发 + Tab 补全 + help | 内部 `Entry` 表把别名映射到子命令；`Invocation` 包装器带实际敲的 alias |

### 命令类（都实现 `SimpleCommand`）

`MsgCommand` `ReplyCommand` `IgnoreCommand` `ToggleCommand` `SpyCommand` `ReloadCommand` `VersionCommand`
—— 每个都只有 `execute` / 部分有 `suggest` / 全部有 `hasPermission`。

⚠️ 没权限的人看到的是「你没权限」，**不是**「命令不存在」；help 和 Tab 补全只显示他真能用的子命令。

---

## 数据流：一条私聊怎么走完

```
/msg 小明 你好   （或 /vw msg，或顶层快捷命令）
  ↓ RootCommand / 快捷命令 → MsgCommand.execute
  ↓ WhisperService.findPlayer(name) → Lookup（player / ambiguous 歧义标记）
  ↓ send() 依次过闸门：
  ↓   ① 是不是自己          ② 找不找得到人 / 名字歧义
  ↓   ③ 服务器名单（双向）  ④ 自己在不在冷却
  ↓   ⑤ 对方关没关接收（toggle.bypass 可强发）
  ↓   ⑥ 对方屏没屏蔽我（ignore.bypass 可强发）
  ↓ deliver()：
  ↓   ① 按 [colors].mode + vwhisper.msg.color 权限渲染消息内容
  ↓   ② 拼 [format].sender / .receiver / .console
  ↓        ↳ 拼的过程中：正文先挂「复制」(ChatTooltip.copy)，最后整条挂 hover+suggest (apply)
  ↓   ③ 给接收者发 + 播 [sound.target]；给发送者发回执 + 播 [sound.sender]
  ↓      （🔴 自言自语两档都不播）
  ↓   ④ 所有开 spy 的人发一份 [format].spy（除 spy.bypass 的人）+ 播 [sound.spy]
  ↓   ⑤ rememberContact(双方) → /reply 可以用了
  ↓   ⑥ log-to-console 开着就往控制台打一行（用 PlainText.of）
```

---

## 铁律（改之前先背）

1. 🔴 **不许用 `PlainComponentSerializer`**。它在 `velocity-api`（provided）里有，IDE 能补全、javac 能过，
   但 **Velocity 4.x 发布 jar 里根本没有** → 运行期 `NoClassDefFoundError`。
   组件转纯文本一律用自研 `PlainText.of()`（只用 `Component.children()` + `TextComponent.content()` 递归，
   都在 `adventure-api` 里）。`tests/ClasspathTest.java` 守着这条。
2. **零第三方依赖**。jar 里不放任何库，Maven 离线装不上 shade 插件。
   要新能力就自己写（TOML 解析、颜色解析都是这么来的）。
3. **主命令名固定小写**。Brigadier literal 大小写敏感。
4. **渐变不单独设权限**：只看 `colors.mode` + `vwhisper.msg.color`。
   但 `strip` 模式下仍要剥掉 `{#FF...>}` 标记，不能漏字面量。
5. **不要加称号/前缀/`%xxx%` 占位符**。用户已定调：代理绕过子服直发，子服 PAPI 取不到，
   硬做要装 PAPIProxyBridge 多一次跨服往返——不值。称号是子服 `Chat.GeneralFormat` 的事。
6. **spy 状态故意不落盘**：重启自动关，免得忘了关还在看别人的私聊。
   `save-ignores` / `save-toggles` 才是落盘的。
7. **命令在起服时注册**：改 `[shortcuts]` 或 `[commands] root` 要**重启代理**，reload 不管这个。
8. `hasPermission` 返回 false 时要给「没权限」提示，不要装作命令不存在。
9. **允许给自己发私聊**（`general.allow-self-message`，默认 `true`）。自聊时的特殊处理都在
   `WhisperService`：只发一条（用 sender 格式）、不查 toggle/ignore、不写 reply 记忆、不广播 spy。
   改动前先想清楚这四条——尤其「不写 reply 记忆」，否则自言自语之后 `/r` 会指向自己，
   把「回复上一个人」这条路堵死。
10. 📌 **`[Tooltip]` 里 `{player}` = 「这条私聊的对方」，不是固定指发送者**。`render()` 每渲染
    一份就要传一个 `otherName`：发送者自己的回执 → 收件人；收件人那份 → 发送者；窥屏那份 → 发送者。
    因为每份的点一下含义不同（要 `/msg` 回去的那个人不一样）。`{server}` / `#server#` 则是固定的
    「发送者所在服」（= `#sender-server#`，「这条消息从哪来」）。
11. 📌 **版本号只改 `pom.xml` 一处**。`@Plugin(version = BuildConstants.VERSION)`，
    `BuildConstants` 由 `src/main/java-templates/` 经 `templating-maven-plugin` 生成。
    🔴 **别把字面量写回注解里**：velocity-api 的注解处理器会在 compile 阶段按注解重写
    `velocity-plugin.json`，写死的话产物里永远是旧版本号（v1.0.2 的 jar 显示 1.0.0 就是这么来的）。
12. 📌 **`ClickEvent` 没有 `value()`**（新版 adventure 把值收进了 `payload()`，
    `ClickEvent.Payload.Text#value()`）。写测试/新代码时别照着老例子抄。
13. 📌 **提示音三档：`[sound]` 总闸 + `[sound.target]` / `[sound.sender]` / `[sound.spy]`**。
    🔴 **自言自语一律不响**（`deliver()` 里 `if (!self)` 包着）——「提醒」只在别人发给你时成立。
    🔴 **老配置升级兜底**：v1.2.0 之前只有一组 `[sound]`（enabled/name/volume/pitch），
    读的时候新键优先、**老键兜底**（`TomlLite.string(m, "sound.target.name", legacyName)`），
    所以服主自己改过的音效不会被静默重置。
    ⚠️ **测试基线 `defaultOverrides()` 里 `sound.enabled = false`**（免得别的用例被干扰），
    写音效用例必须自己在 overrides 里把总闸打开 —— 我第一次就是全绿转全红卡在这。

---

## 提示音（`[sound]`）

三档共用 `SoundCue`：`enabled` / `name` / `volume` / `pitch` / `source`。

- 🔴 **音效 id 在构造时校验 + 预构造 `Sound`**，不放进 `sound()` 现算。
  `Key.key()` 拒绝大写/空格/非法符号，从 wiki 抄一个带大写的 id 就抛 `InvalidKeyException`；
  等到发消息那一刻才炸会连带吞掉 reply 记忆、窥屏、控制台日志（消息本体倒已经发出去了）。
  校验不过就退回 `SoundCue.DEFAULT_NAME`，起服一眼能在速览里看出来。
- 📌 `source` 决定这条音效归游戏设置里哪个音量滑块管（master/music/…/ui，写歪退回 `player`）。
  提示音设成 `master` 更稳 —— 玩家把「玩家」那栏调静音时，私聊提示不会跟着没。
- 起服速览在 `VWhisper.reportConfig()` 里，总闸关着时只打一行「总闸关（三档都不响）」，不刷屏。

### 🔴🔴 必须调 `playSound(Sound, Emitter)`，单参数那个是空实现

`Player#playSound(Sound)`（以及带 xyz 坐标那个）在 Velocity 里**方法体是空的** —— 不报错、
不出声、不打日志，配置全对也照样静音。真正被 `ConnectedPlayer` 实现、会发出
`ClientboundSoundEntityPacket` 的只有 **`playSound(Sound, Emitter)`**，emitter 传
`Sound.Emitter.self()`（adventure 里它是单例，Velocity 内部用 `==` 比对，安全）。

> 官方口径：<https://docs.papermc.io/velocity/dev/pitfalls/>
> “Player#playSound(Sound) is not implemented, as Adventure’s contract requires sounds
> to play at the player’s current position.”

配套的两条限制（都是 Velocity 侧 `ConnectedPlayer.playSound` 里的早退分支）：

- 客户端 **< 1.19.3** 直接 `return`，不发；
- emitter 为 self 时拿 `getConnectedServer()` 的实体 id 当发声点 ——
  玩家**还没连上后端服**时 `return`。所以 `playCue()` 里先判 `getCurrentServer().isEmpty()` 再调，
  免得白跑一趟（也避免 `getEntityId()` 为 null 时抛 NPE 被 catch 记一条没意义的 warn）；
- emitter 若传**另一个玩家**，两人必须**在同一个子服**，否则同样静默 `return`。

`ServiceTest` 里 `Fake.soundsUsedEmitter()` 会记录每次 `playSound` 的参数个数并断言等于 2 ——
**桩里只比对 `Sound` 对象是测不出这个 bug 的**（旧代码就是这么漏过去的）。

---

## 🔴 颜色继承：`#message#` 是兄弟节点，不是子节点

`render()` 的做法是：先替换占位符 → 按 `#message#` 切开 → 每段各自解析成组件 →
**消息组件和这些段是平级的兄弟**，一起挂到同一个空根节点下。

于是有个反直觉的坑：

```text
模板   &8[&7我&8]&r&7 #message#
解析   root(text "") ─┬─ head: [ "["灰黑, "我"灰, "]"灰黑, " "灰 ]
                      └─ message: text("你好") color=null   ← 兄弟！继承不到那个 &7
```

adventure 的样式只从**父**往下传，兄弟之间不传。`&7` 只染了它后面那一个空格，
消息自己没颜色 → 客户端回落到默认白。**配置里怎么调都调不出来，必须代码里处理。**

目前的解法（`WhisperService` 里三个小方法）：

| 方法 | 干什么 |
| --- | --- |
| `styleAt(config, text)` | 在 text 尾巴接一个探针字符 `U+E002` 再解析，找到含探针的节点读它的 `Style` |
| `carry(message, style)` | 包一层空文本的**父**组件带上这个样式（颜色 + 装饰 + 字体） |
| `walk(root, visitor)` | 迭代式深搜，找探针节点 |

为什么要探针：颜色码只作用于它后面的字符，`&7#message#` 这种「紧贴」写法解析出来
压根没有落点节点，光看解析结果问不出颜色。探针保证一定有落点，且正好落在消息的位置上。

为什么用父组件包一层而不是直接改消息：子组件上显式设了的值会盖掉父组件传下来的 ——
这样消息里玩家自己写的 `&c` 照旧赢，只填「没设」的洞。

⚠️ 探针字符要和 `ChatColors` 里渐变用的哨兵（`U+E000`/`U+E001`）错开。
⚠️ `&r` 之后没跟颜色码 → 探到的是空样式 → `carry` 原样返回 → 消息保持客户端默认（白），
   老行为不变（随包默认配置就是 `&r #message#`）。

---

## 改动地图

| 想改什么 | 改哪 | 连带要动 |
|---|---|---|
| 加配置项 | `Configuration.java` + `src/main/resources/config.toml` | README 表格、`VWhisper.reportConfig()` |
| 加子命令 | `command/` 新类 + `RootCommand` 的 `Entry` 表 | `[commands]` 段、`[shortcuts]`（可选）、help 文案 |
| 加/改提示语 | `config.toml` 的 `[messages]` | `#label#` 会自动换成实际命令名，别硬写 `/vw` |
| 颜色/渐变语法 | `ChatColors.java` | ⚠️ Vmessage 有一份独立拷贝，两边都要改 |
| 裸 hex `#RRGGBB` | `ChatColors.BARE_HEX_6` | 🔴 两个断言 `(?<![&§{])` / `(?![0-9a-fA-F])` 缺一不可；在 `STRIP` 里**必须排最后**、`normalize()` 里**最后一步**。只认 6 位（裸 3 位 `#666` 是网络用语，会误伤） |
| `#message# 的颜色/装饰怎么带 | `WhisperService.styleAt/carry/walk` | 见上节。`parse()` 解析失败会退成纯文本，探针也会跟着退化 → 拿不到样式 → 兜底不染色，不会炸 |
| 权限判定 | `Permissions.java` | 注意 `allow-by-default` 只覆盖 4 个基础节点 |
| 存盘格式 | `Store.java` | 纯文本一行一条 UUID，肉眼可读；改格式要考虑旧文件兼容 |
| 自聊相关 | `WhisperService.send()` ③⑤⑥ + `deliver()` | 自聊有 4 条特殊规则（见铁律 9），分散在两个方法里，改一处容易漏另一处 |

---

## 构建 & 测试

```bash
cd D:/Code/mc/plugins/VWhisper
JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B -o clean package
```

### 测试（⚠️ 用 PowerShell 跑，别用 Git Bash）

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
# 工具（非测试）：打印某份真实配置解析出来的值
& "$jdk\java.exe" -cp "$cp;C:\Users\PC\.m2\repository\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar;$out" `
    ConfigProbe D:\game\test_velocity\velocity\plugins\vwhisper
```

| 测试 | 断言 | 覆盖 |
|---|---|---|
| `SmokeTest` | 64 | TOML 解析（含**无引号值的行尾注释、多行数组、段名行带注释的 `]`**）、颜色/渐变渲染。**必须传 `target/classes/config.toml` 作 `args[0]`** |
| `ServiceTest` | 105 | 用动态代理桩掉 Velocity API，跑真实 `WhisperService`/`MsgCommand`/`ToggleCommand`：权限闸门、接收开关（**含 `/vw toggle on` 方向**）、屏蔽与**存盘往返**、窥屏、冷却、颜色权限、服务器名单、Tab 补全、**#message# 的样式继承**、**悬停/点击（`[Tooltip]` 两档 19 条）**、**提示音三档（19 条，含老配置升级兜底 + 必须走带 Emitter 的重载）** |
| `ClasspathTest` | 3 | 隔离 ClassLoader 只加载 velocity jar + 本项目 classes，验证工具类可加载，**并反向验证旧写法在同一环境确实挂**（否则这测试是自欺欺人） |

> 📌 `ConfigProbe` **不是测试，是排障工具**：传一个配置目录（或 `config.toml` 路径），
> 用插件自己的 `Configuration.load()` 读一遍，把 `[Tooltip]`、`[sound]` 三档、
> 三个 `format` 串解析出来的值打出来 —— 换 jar / 改完线上配置后跑一下，
> 不用起服就能确认「配置真读进去了」。
> ⚠️ classpath 要额外带 `slf4j-api`（`Configuration.load(dir, logger)` 要 `Logger` 参数）。

> ⚠️ 写新用例时**测完要把状态还原**（例如 `toggleIgnore` 加了就要撤掉）：
> 这些用例共享同一批玩家与同一个 `Store`，后面还有颜色之类的用例，
> 一旦留下「Bob 屏蔽了 Alice」这种残留，后面的用例会以奇怪的方式炸掉。

---

## 兄弟项目 & 上下游

| 项目 | 关系 |
|---|---|
| `Vmessage` | 管公共跨服聊天，两者各管一段不冲突。`ChatColors` 是各自一份独立拷贝 |
| `VTpa` | 兄弟项目，管传送请求。`TomlLite` / `Permissions` 是各自一份独立拷贝 |
| LuckPerms | **不是硬依赖**。权限走 Velocity 原生 `CommandSource#getPermissionValue`，谁提供权限都行 |
| PAPIProxyBridge | **不依赖、不用装** |

⚠️ 上线状态：`vwhisper-1.2.2.jar` 已在本地测试服 `D:\game\test_velocity\velocity\plugins\` 就位，
**线上尚未部署**。

---

## 排查速查

| 现象 | 看什么 |
|---|---|
| `/msg` 一执行就炸 `NoClassDefFoundError` | 有人引入了 Velocity jar 里不存在的类。跑 `ClasspathTest`，并按 README 的办法：把所有 import（跳过 `java.*`/本项目/wildcard）逐个到 `velocity-*.jar` 找 `.class` |
| 敲 `/msg` 命令不存在 | 快捷命令没注册上（起服日志会 WARN）。改用 `/vw msg`，或查是不是被别的插件占了 |
| 子服 CMI 的 `/msg` 失效了 | **这是故意的**。代理注册了 `/msg` 就不往后端转发。想要子服自己的就把 `[shortcuts]` 那行删掉重启 |
| 改了别名不生效 | 命令起服时注册，要重启代理 |
| 控制台日志乱码/吃字 | `PlainText.of()` 递归有问题，检查 `TextComponent.content()` 之外有没有漏 translatable/score 等组件类型 |
| 消息内容颜色不对（配了 `&7 #message#` 还是白的） | 先看是不是踩了上面「颜色继承」那节：消息是兄弟节点。已经修好了，若还不对就查 `styleAt()` 探到的样式是不是空（用探针单独打一遍） |

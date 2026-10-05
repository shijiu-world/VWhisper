# agent.md — VWhisper（跨服私聊）

> 给 AI 上手用的**代码地图**。服主视角的「怎么装、怎么配」在 `README.md`，本文档不重复。
> 本文档讲：代码在哪、改一处会牵动什么、哪些东西碰了就出事。

## 一句话定位

**纯代理端**插件，子服一个插件都不用装。消息是代理对着 `target.sendMessage()` 直接发出去的，
后端服务器根本不知道有这条消息 —— 这就是「跨服」的全部原理。

- 源码：`D:\Code\mc\plugins\VWhisper`
- 仓库：`git@github.com:shijiu-world/VWhisper.git`（**走 SSH**，https 会被本机代理掐断 502）
- 产物：`target/vwhisper-1.0.1.jar`（class 61，Velocity 3.4+ ~ 4.x 通用）
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
| `ChatColors.java` | 237 | 自研颜色解析器：`&c` / `&#RRGGBB` / `&x&F&F...` / `{#F00}` / 渐变 | 与 Vmessage 那份**同源但独立**，改语法两边都要改 |
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
  ↓   ③ 给接收者发 + 播提示音；给发送者发回执
  ↓   ④ 所有开 spy 的人发一份 [format].spy（除 spy.bypass 的人）
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
```

| 测试 | 断言 | 覆盖 |
|---|---|---|
| `SmokeTest` | 49 | TOML 解析、颜色/渐变渲染。**必须传 `target/classes/config.toml` 作 `args[0]`** |
| `ServiceTest` | 57 | 用动态代理桩掉 Velocity API，跑真实 `WhisperService`/`MsgCommand`：权限闸门、接收开关、屏蔽、窥屏、冷却、颜色权限、服务器名单、Tab 补全、**#message# 的样式继承** |
| `ClasspathTest` | 3 | 隔离 ClassLoader 只加载 velocity jar + 本项目 classes，验证工具类可加载，**并反向验证旧写法在同一环境确实挂**（否则这测试是自欺欺人） |

---

## 兄弟项目 & 上下游

| 项目 | 关系 |
|---|---|
| `Vmessage` | 管公共跨服聊天，两者各管一段不冲突。`ChatColors` 是各自一份独立拷贝 |
| `VTpa` | 兄弟项目，管传送请求。`TomlLite` / `Permissions` 是各自一份独立拷贝 |
| LuckPerms | **不是硬依赖**。权限走 Velocity 原生 `CommandSource#getPermissionValue`，谁提供权限都行 |
| PAPIProxyBridge | **不依赖、不用装** |

⚠️ 上线状态：`vwhisper-1.0.1.jar` 已在本地测试服 `D:\game\test_velocity\velocity\plugins\` 就位，
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

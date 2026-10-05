package cn.shijiu.vwhisper;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家的私聊状态：屏蔽名单、接收开关、窥屏开关、/reply 的上一个对象。
 *
 * <p>纯内存 + 两个纯文本文件（{@code plugins/vwhisper/ignores.txt}、{@code toggles.txt}），
 * 一行一条，肉眼可读、丢了也不影响插件起来。
 * ⚠️ 窥屏（spy）状态故意不落盘 —— 代理一重启就自动关掉，免得哪天忘了关还在偷看。
 */
public final class Store {

    private final Path directory;
    private final Logger logger;

    /** 被谁屏蔽了谁：owner -> targets。 */
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    /** 关掉接收私聊的人。 */
    private final Set<UUID> toggledOff = ConcurrentHashMap.newKeySet();
    /** 正在窥屏的人。 */
    private final Set<UUID> spying = ConcurrentHashMap.newKeySet();
    /** /reply 的对象：我最近一次跟谁聊过。 */
    private final Map<UUID, UUID> lastContact = new ConcurrentHashMap<>();
    /** 存盘串行化：两个人的操作可能同时触发全量覆写。 */
    private final Object saveLock = new Object();
    /**
     * 启动时没读出来 —— 之后一律不许再写。
     *
     * <p>这时候内存里的数据跟磁盘上的对不上号，写下去就是把别人的记录冲掉。
     */
    private volatile boolean loadFailed;

    public Store(final Path directory, final Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // 屏蔽
    // ------------------------------------------------------------------

    public boolean isIgnoring(final UUID owner, final UUID target) {
        final Set<UUID> set = ignored.get(owner);
        return set != null && set.contains(target);
    }

    /**
     * 切换屏蔽状态；返回 true 表示这次是「新增屏蔽」。
     *
     * <p>⚠️ 「增删 + 空了就删这个键」必须是一次 {@code compute} 做完：
     * 拆成两步（先 contains/remove，再判 isEmpty 再 remove(this)）时，
     * 两个人并发操作同一个 owner 会互相把刚加进去的条目连锅端掉。
     */
    public boolean toggleIgnore(final UUID owner, final UUID target, final boolean save) {
        final boolean[] added = new boolean[1];
        ignored.compute(owner, (uuid, set) -> {
            final Set<UUID> current = set == null ? ConcurrentHashMap.newKeySet() : set;
            if (current.contains(target)) {
                current.remove(target);
                added[0] = false;
            } else {
                current.add(target);
                added[0] = true;
            }
            return current.isEmpty() ? null : current;
        });
        if (save) {
            saveIgnores();
        }
        return added[0];
    }

    /** 只读快照（副本）—— 别把内部那个并发集合直接交出去。 */
    public Map<UUID, Set<UUID>> ignores() {
        final Map<UUID, Set<UUID>> copy = new HashMap<>();
        for (final Map.Entry<UUID, Set<UUID>> entry : ignored.entrySet()) {
            copy.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
        }
        return copy;
    }

    // ------------------------------------------------------------------
    // 接收开关
    // ------------------------------------------------------------------

    /** 这个人开着接收私聊吗（默认开）。 */
    public boolean isReceiving(final UUID uuid) {
        return !toggledOff.contains(uuid);
    }

    /** 设置是否接收私聊。 */
    public void setReceiving(final UUID uuid, final boolean receiving, final boolean save) {
        if (receiving) {
            toggledOff.remove(uuid);
        } else {
            toggledOff.add(uuid);
        }
        if (save) {
            saveToggles();
        }
    }

    /** 只读快照（副本）—— 别把内部那个并发集合直接交出去。 */
    public Set<UUID> toggledOff() {
        return new java.util.HashSet<>(toggledOff);
    }

    // ------------------------------------------------------------------
    // 窥屏
    // ------------------------------------------------------------------

    public boolean isSpying(final UUID uuid) {
        return spying.contains(uuid);
    }

    public void setSpying(final UUID uuid, final boolean spy) {
        if (spy) {
            spying.add(uuid);
        } else {
            spying.remove(uuid);
        }
    }

    // ------------------------------------------------------------------
    // /reply 记忆
    // ------------------------------------------------------------------

    /** A 和 B 刚聊过：两边都记一下，谁都能接着 /r。 */
    public void rememberContact(final UUID a, final UUID b) {
        lastContact.put(a, b);
        lastContact.put(b, a);
    }

    /** 我该回复谁；没聊过就是 null。 */
    public UUID contact(final UUID me) {
        return lastContact.get(me);
    }

    /** 下线清掉：自己这条、以及别人记着的指向自己的那条。 */
    /**
     * 下线清掉会话级状态：/reply 记忆、窥屏。
     *
     * <p>⚠️ **这里绝不能动 `toggledOff`**：那是玩家主动关掉私聊接收的长期偏好，
     * 也是唯一会落盘的状态（`toggles.txt`）。以前这里一并清了，结果玩家重连后
     * 「以为关着的私聊」又进来了，而磁盘上那条记录还在——下次有人敲一次
     * `/msgtoggle` 触发全量覆写，他的记录就被彻底抹掉了。
     */
    public void forget(final UUID leaving) {
        lastContact.remove(leaving);
        lastContact.values().removeIf(leaving::equals);
        spying.remove(leaving);
    }

    // ------------------------------------------------------------------
    // 存盘
    // ------------------------------------------------------------------

    /**
     * 把两个文件读进内存。
     *
     * <p>⚠️ 顺序很要紧：**先读全再替换**，绝不「先 clear 再读」。
     * 磁盘读写是有可能失败的（磁盘满、权限、编辑器锁、别的进程写了一半），
     * 一旦先清了内存、接着读失败返回空，插件看起来一切正常，
     * 但下一次有人敲 `/ignore` 触发全量覆写时，全校所有人的屏蔽记录就没了，且不可恢复。
     * 所以读失败时：什么都不改，并置起 {@link #loadFailed} 让后续写入直接跳过。
     */
    public void load() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        final Map<UUID, Set<UUID>> loadedIgnores = new HashMap<>();
        final Path ignoreFile = directory.resolve("ignores.txt");
        if (Files.exists(ignoreFile)) {
            final List<String> lines = read(ignoreFile);
            if (lines == null) {
                loadFailed = true;
                return;
            }
            for (final String line : lines) {
                final String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    final UUID owner = UUID.fromString(parts[0]);
                    final UUID target = UUID.fromString(parts[1]);
                    loadedIgnores.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(target);
                } catch (final IllegalArgumentException ignoredUuid) {
                    // 手改坏了的行跳过就行
                }
            }
        }
        final Set<UUID> loadedToggles = ConcurrentHashMap.newKeySet();
        final Path toggleFile = directory.resolve("toggles.txt");
        if (Files.exists(toggleFile)) {
            final List<String> lines = read(toggleFile);
            if (lines == null) {
                loadFailed = true;
                return;
            }
            for (final String line : lines) {
                try {
                    loadedToggles.add(UUID.fromString(line.trim()));
                } catch (final IllegalArgumentException ignoredUuid) {
                    // 同上
                }
            }
        }
        ignored.clear();
        ignored.putAll(loadedIgnores);
        toggledOff.clear();
        toggledOff.addAll(loadedToggles);
        loadFailed = false;
    }

    public void saveIgnores() {
        if (guardFailedWrite("ignores")) {
            return;
        }
        final List<String> lines = new ArrayList<>();
        for (final Map.Entry<UUID, Set<UUID>> entry : ignored.entrySet()) {
            final Set<UUID> snapshot = new java.util.HashSet<>(entry.getValue());
            for (final UUID target : snapshot) {
                lines.add(entry.getKey() + " " + target);
            }
        }
        write(directory.resolve("ignores.txt"), lines);
    }

    public void saveToggles() {
        if (guardFailedWrite("toggles")) {
            return;
        }
        final List<String> lines = new ArrayList<>();
        for (final UUID uuid : toggledOff) {
            lines.add(uuid.toString());
        }
        write(directory.resolve("toggles.txt"), lines);
    }

    /**
     * 读不出来就别写 —— 宁可这次设置丢了，也不能用半截数据把文件覆写掉。
     *
     * @return true 表示这次写入应当跳过
     */
    private boolean guardFailedWrite(final String what) {
        if (!loadFailed) {
            return false;
        }
        logger.warn("[vwhisper] 启动时 " + what + " 没读出来，为安全起见跳过这次写入"
                + "（修好读取问题后重启代理即可恢复）");
        return true;
    }

    /**
     * 读一个纯文本文件。
     *
     * @return 行内容；**读失败返回 null**（不是空列表——空列表会被当成「文件是空的」）。
     */
    private List<String> read(final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            logger.warn("[vwhisper] 读 " + file.getFileName() + " 失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 全量覆写。
     *
     * <p>⚠️ 必须「写临时文件 → ATOMIC_MOVE」，不能直接盯着目标文件 TRUNCATE：
     * 写到一半进程被杀 / 磁盘满会把文件截成半截，下次启动正好触发
     * {@link #load()} 里「读失败」那条路。
     *
     * <p>写入统一串行（{@link #saveLock}）：两个人同时敲 `/ignore` 时，
     * 两次全量覆写交错写同一个文件，结果是一份混合的老格式。
     */
    private void write(final Path file, final List<String> lines) {
        synchronized (saveLock) {
            try {
                Files.createDirectories(directory);
                final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    for (final String line : lines) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (final AtomicMoveNotSupportedException notAtomic) {
                    // 个别文件系统不支持原子替换，退回普通覆盖（至少不会留下半截 tmp）
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                logger.warn("[vwhisper] 写 " + file.getFileName() + " 失败：" + e.getMessage());
            }
        }
    }
}

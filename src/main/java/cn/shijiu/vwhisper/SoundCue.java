package cn.shijiu.vwhisper;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * 一档提示音：开不开、放哪个音、音量音调、走哪个音量滑块。
 *
 * <p>三档共用这一份结构 —— {@code [sound.target]} 收到私聊的人、
 * {@code [sound.sender]} 发出私聊的人（回执）、{@code [sound.spy]} 开着窥屏的人。
 *
 * <p>🔴 音效 id 在**构造时**就校验并固定下来，不放进 {@link #sound()} 里现算：
 * {@code Key.key()} 拒绝大写字母 / 空格 / 非法符号，从 wiki 上抄一个带大写的 id
 * 下来就会抛 {@code InvalidKeyException}。等到发消息那一刻才炸，会连带把 reply 记忆、
 * 窥屏、控制台日志整段吞掉（消息本体倒已经发出去了），排查起来非常难受。
 */
public final class SoundCue {

    /** 配错了（大写、空格、非法符号、留空）就退回它。 */
    static final String DEFAULT_NAME = "minecraft:entity.experience_orb.pickup";

    private final boolean enabled;
    private final String name;
    private final float volume;
    private final float pitch;
    private final Sound.Source source;
    /** 预构造好的音效；{@link #name} 已经过校验，所以这里不会抛。 */
    private final Sound sound;

    private SoundCue(final boolean enabled, final String name, final float volume,
                     final float pitch, final Sound.Source source) {
        this.enabled = enabled;
        this.name = name;
        this.volume = volume;
        this.pitch = pitch;
        this.source = source;
        this.sound = Sound.sound(Key.key(name), source, volume, pitch);
    }

    /**
     * 从配置里造一档提示音。
     *
     * @param enabled 这一档开不开（总闸在 {@link Configuration#soundEnabled()}，两者都要为真才响）
     * @param rawName 音效 id；非法或留空时退回 {@link #DEFAULT_NAME}
     * @param volume  音量
     * @param pitch   音调
     * @param rawSource 音量滑块名（游戏设置里的分类）；写歪退回 {@code PLAYER}
     */
    static SoundCue of(final boolean enabled, final String rawName, final float volume,
                       final float pitch, final String rawSource) {
        final String name = valid(rawName) ? rawName.trim() : DEFAULT_NAME;
        return new SoundCue(enabled, name, volume, pitch, parseSource(rawSource));
    }

    /** 这个字符串能不能当 adventure 的 {@code Key} 用（要求 {@code [a-z0-9_.-]}:）。 */
    static boolean valid(final String candidate) {
        if (candidate == null) {
            return false;
        }
        final String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            return !Key.key(trimmed).asString().isEmpty();
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * 音量滑块：决定这条音效归游戏设置里哪个分类管。
     *
     * <p>老写法固定用 {@code PLAYER}；其实「提示音」归 {@code MASTER} 更合适 ——
     * 玩家把「玩家」那一栏调小甚至静音时，私聊提示就跟着没了。
     * 所以这里开放成一个配置项，写歪退回 {@code PLAYER}（保持老行为）。
     */
    static Sound.Source parseSource(final String raw) {
        if (raw == null) {
            return Sound.Source.PLAYER;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Sound.Source.PLAYER;
        }
        for (final Sound.Source candidate : Sound.Source.values()) {
            if (candidate.name().equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        return Sound.Source.PLAYER;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 实际生效的音效 id（已经过校验，配错时这里是默认值）。 */
    public String name() {
        return name;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    public Sound.Source source() {
        return source;
    }

    /** 直接喂给 {@code player.playSound(...)}。 */
    public Sound sound() {
        return sound;
    }

    /** 起服速览里的一行描述；关着就一个「关」字，别刷屏。 */
    public String describe() {
        if (!enabled) {
            return "关";
        }
        return name + "（音量 " + volume + " 音调 " + pitch + "，" + source.name().toLowerCase() + "）";
    }
}

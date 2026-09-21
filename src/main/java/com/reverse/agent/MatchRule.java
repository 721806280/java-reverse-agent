package com.reverse.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一条"指纹匹配规则"——用混淆免疫的特征描述某个目标类。
 * <p>
 * 不含目标类名，只用 {@link ClassFingerprint} 的语义字段做多重命中。
 * 多个条件同时满足才算命中，降低误伤。
 */
public final class MatchRule {

    /**
     * FORCE_GETTER_VALUE 对 long/Long 返回型 getter 写入的远期时间戳常量（约公元 2123 年）——
     * 远超任何合理有效期，用于让「返回时间戳」型的判断恒落在未来。
     */
    static final long FAR_FUTURE_MILLIS = 4859711999000L;

    /**
     * 规则名，仅用于日志与自检报告。
     */
    final String name;

    /**
     * 必须命中的 @SerializedName 值。
     */
    final Set<String> requireSerials;

    /**
     * 必须作为子串命中的字符串常量。
     */
    final Set<String> requireStringContains;

    /**
     * 必须命中的 Kotlin getter 语义名。
     */
    final Set<String> requireGetters;

    /**
     * 命中后对该类执行的动作。
     */
    final Action action;

    /**
     * 期望的最小命中类数——实际命中低于此值即视为「锚点疑似失效」（目标改版/重新混淆）。
     */
    final int minHits;

    /**
     * 是否核心规则——核心规则未达 {@link #minHits} 时单独高危告警。
     */
    final boolean core;

    private MatchRule(String name, Set<String> requireSerials,
                      Set<String> requireStringContains, Set<String> requireGetters,
                      Action action, int minHits, boolean core) {
        this.name = name;
        this.requireSerials = requireSerials;
        this.requireStringContains = requireStringContains;
        this.requireGetters = requireGetters;
        this.action = action;
        this.minHits = minHits;
        this.core = core;
    }

    /**
     * 所有非空要求集都需被包含才算命中。
     */
    boolean matches(ClassFingerprint fp) {
        if (!requireSerials.isEmpty()
                && !fp.serialNames.containsAll(requireSerials)) {
            return false;
        }
        if (!requireStringContains.isEmpty()) {
            for (String needle : requireStringContains) {
                boolean found = false;
                for (String c : fp.stringConstants) {
                    if (c.contains(needle)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
        }
        if (!requireGetters.isEmpty()) {
            for (String g : requireGetters) {
                if (!fp.kotlinGetters.contains(g)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "Rule[" + name + "] serials=" + requireSerials
                + " contains=" + requireStringContains
                + " getters=" + requireGetters
                + " action=" + action;
    }

    public enum Action {
        /**
         * 把方法体换成「直接 gson 反序列化单个 String 参数为其声明的引用返回类型」，
         * 绕过原有的 Base64+RSA 解密，使明文 JSON 也能被解析。
         */
        DESERIALIZE_JSON,

        /**
         * 强制无参 getter 返回固定值，按返回形状分派：
         * {@code ()Z}/{@code ()Ljava/lang/Boolean;} 返回 true，
         * {@code ()J}/{@code ()Ljava/lang/Long;} 返回远期时间戳。
         * 若规则设了 {@link #requireGetters}，只改名字命中的 getter；
         * 否则按返回形状定位，不依赖方法名。
         */
        FORCE_GETTER_VALUE,

        /**
         * 把命中类的所有无参 {@code ()V} 方法整体清空为直接 {@code return}。
         * 用于从源头关停自成一体的过程（如后台线程入口/工作/回调方法），比"改结果"更彻底。
         * 只清无参 void，带参与带返回值的方法保持原样。
         */
        SILENCE_VOID
    }

    /**
     * 构建器。
     */
    public static final class Builder {
        private final String name;
        private final Set<String> serials = new LinkedHashSet<>();
        private final Set<String> stringContains = new LinkedHashSet<>();
        private final Set<String> getters = new LinkedHashSet<>();
        private Action action = Action.FORCE_GETTER_VALUE;
        private int minHits = 1;
        private boolean core = false;

        public Builder(String name) {
            this.name = name;
        }

        public Builder serial(String... values) {
            serials.addAll(Arrays.asList(values));
            return this;
        }

        public Builder stringContains(String... values) {
            stringContains.addAll(Arrays.asList(values));
            return this;
        }

        public Builder getter(String... values) {
            getters.addAll(Arrays.asList(values));
            return this;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public Builder minHits(int minHits) {
            this.minHits = minHits;
            return this;
        }

        public Builder core(boolean core) {
            this.core = core;
            return this;
        }

        public MatchRule build() {
            return new MatchRule(name,
                    Collections.unmodifiableSet(serials),
                    Collections.unmodifiableSet(stringContains),
                    Collections.unmodifiableSet(getters),
                    action, minHits, core);
        }
    }
}

package com.reverse.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一条"指纹匹配规则"——用混淆免疫的特征描述某个目标类。
 * <p>
 * 不含类名，只用 {@link ClassFingerprint} 的语义字段做多重命中。
 * 多个条件同时满足才算命中，降低误伤。
 */
public final class MatchRule {

    /**
     * 规则名，仅用于日志
     */
    final String name;

    /**
     * 必须命中的 @SerializedName 值（交集为空则跳过此项）
     */
    final Set<String> requireSerials;

    /**
     * 必须整串命中的字符串常量
     */
    final Set<String> requireStrings;

    /**
     * 必须作为子串命中的字符串常量（用于长文案内嵌的 URL/关键词）
     */
    final Set<String> requireStringContains;

    /**
     * 必须命中的 Kotlin getter 语义名
     */
    final Set<String> requireGetters;

    /**
     * 可选的方法描述符过滤器。为空时表示该规则可作用于类中的所有方法。
     * 使用 JVM descriptor 而不是混淆后的方法名，避免同名重载误改。
     */
    final Set<String> requireMethodDescriptors;

    /**
     * 命中后对该类执行的动作
     */
    final Action action;

    private MatchRule(String name, Set<String> requireSerials,
                      Set<String> requireStrings, Set<String> requireStringContains,
                      Set<String> requireGetters, Set<String> requireMethodDescriptors,
                      Action action) {
        this.name = name;
        this.requireSerials = requireSerials;
        this.requireStrings = requireStrings;
        this.requireStringContains = requireStringContains;
        this.requireGetters = requireGetters;
        this.requireMethodDescriptors = requireMethodDescriptors;
        this.action = action;
    }

    /**
     * 判断指纹是否命中本规则——所有非空要求集都需被包含。
     */
    boolean matches(ClassFingerprint fp) {
        if (!requireSerials.isEmpty()
                && !fp.serialNames.containsAll(requireSerials)) {
            return false;
        }
        if (!requireStrings.isEmpty()) {
            for (String s : requireStrings) {
                if (!fp.containsString(s)) {
                    return false;
                }
            }
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

    /**
     * 判断某个方法是否属于本规则的可改写范围。
     */
    boolean matchesMethod(String descriptor) {
        return requireMethodDescriptors.isEmpty()
                || requireMethodDescriptors.contains(descriptor);
    }

    @Override
    public String toString() {
        return "Rule[" + name + "] serials=" + requireSerials
                + " strings=" + requireStrings
                + " contains=" + requireStringContains
                + " getters=" + requireGetters
                + " methods=" + requireMethodDescriptors
                + " action=" + action;
    }

    /**
     * 命中后做什么
     */
    public enum Action {
        /**
         * 清空无返回值方法体（针对校验入口）
         */
        NEUTRALIZE,

        /**
         * 让 boolean getter 恒返回 true（针对 getValid 闸门）
         */
        FORCE_TRUE,

        /**
         * 只记录命中、不改字节码（dry-run / 调试用）
         */
        LOG_ONLY,

        /**
         * 返回一个表示成功的对象（当前用于连通性探测，避免调用方收到 null）。
         */
        RETURN_SUCCESS,

        /**
         * 将单个 {@code String} 参数反序列化为方法声明的引用返回类型。
         */
        DESERIALIZE_JSON,

        /**
         * 清空规则范围内的全部方法体（含带参）：void 直接 return，引用返回 null，
         * boolean 返回 true，其余 primitive 返回 0。不受"只改第一个"限制；配置了
         * {@link Builder#methodDescriptor(String...)} 时，只处理指定签名。
         */
        NEUTRALIZE_ALL
    }

    /**
     * 构建器
     */
    public static final class Builder {
        private final String name;
        private final Set<String> serials = new LinkedHashSet<>();
        private final Set<String> strings = new LinkedHashSet<>();
        private final Set<String> stringContains = new LinkedHashSet<>();
        private final Set<String> getters = new LinkedHashSet<>();
        private final Set<String> methodDescriptors = new LinkedHashSet<>();
        private Action action = Action.NEUTRALIZE;

        public Builder(String name) {
            this.name = name;
        }

        /**
         * 要求该类的 {@code @SerializedName} 里含此值
         */
        public Builder serial(String... values) {
            serials.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * 要求该类常量池里整串含此字符串
         */
        public Builder string(String... values) {
            strings.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * 要求该类某一常量包含此子串（适合内嵌在长文案里的 URL/关键词）
         */
        public Builder stringContains(String... values) {
            stringContains.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * 要求该类 Kotlin @Metadata 含此 getter 名
         */
        public Builder getter(String... values) {
            getters.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * 限定规则只作用于指定 JVM 方法描述符（例如 {@code ()V}）。
         */
        public Builder methodDescriptor(String... values) {
            methodDescriptors.addAll(Arrays.asList(values));
            return this;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public MatchRule build() {
            return new MatchRule(name,
                    Collections.unmodifiableSet(serials),
                    Collections.unmodifiableSet(strings),
                    Collections.unmodifiableSet(stringContains),
                    Collections.unmodifiableSet(getters),
                    Collections.unmodifiableSet(methodDescriptors),
                    action);
        }
    }
}

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

    /** 规则名，仅用于日志。 */
    final String name;

    /** 必须命中的 @SerializedName 值。 */
    final Set<String> requireSerials;

    /** 必须整串命中的字符串常量。 */
    final Set<String> requireStrings;

    /**
     * 改写目标方法必须包含的全部字符串常量。空集表示不做方法级限定。
     */
    final Set<String> requireMethodStrings;

    /** 必须作为子串命中的字符串常量。 */
    final Set<String> requireStringContains;

    /** 必须命中的 Kotlin getter 语义名。 */
    final Set<String> requireGetters;

    /**
     * 可选的方法形状过滤器。为空时作用于所有方法；
     * 只比较参数数量和返回类型，不包含混淆后的包名/类名。
     */
    final Set<String> requireMethodDescriptors;

    /** 命中后对该类执行的动作。 */
    final Action action;

    private MatchRule(String name, Set<String> requireSerials,
                      Set<String> requireStrings, Set<String> requireStringContains,
                      Set<String> requireGetters, Set<String> requireMethodStrings,
                      Set<String> requireMethodDescriptors,
                      Action action) {
        this.name = name;
        this.requireSerials = requireSerials;
        this.requireStrings = requireStrings;
        this.requireMethodStrings = requireMethodStrings;
        this.requireStringContains = requireStringContains;
        this.requireGetters = requireGetters;
        this.requireMethodDescriptors = requireMethodDescriptors;
        this.action = action;
    }

    /** 所有非空要求集都需被包含才算命中。 */
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
        if (!requireMethodStrings.isEmpty()) {
            boolean found = fp.methodStrings.values().stream()
                    .anyMatch(strings -> strings.containsAll(requireMethodStrings));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    boolean matchesMethodStrings(ClassFingerprint fingerprint, String descriptor) {
        return requireMethodStrings.isEmpty()
                || fingerprint.stringsForMethod(descriptor).containsAll(requireMethodStrings);
    }

    /** 判断方法形状是否属于本规则的可改写范围。 */
    boolean matchesMethod(String descriptor) {
        return requireMethodDescriptors.isEmpty()
                || requireMethodDescriptors.contains(methodShape(descriptor));
    }

    static String methodShape(String descriptor) {
        int argumentsEnd = descriptor.indexOf(')');
        String arguments = descriptor.substring(1, argumentsEnd);
        String returnType = descriptor.substring(argumentsEnd + 1);
        return "args=" + countDescriptors(arguments) + ",return=" + returnTypeKind(returnType);
    }

    private static int countDescriptors(String arguments) {
        int count = 0;
        for (int i = 0; i < arguments.length(); i++) {
            count++;
            i += descriptorLength(arguments, i) - 1;
        }
        return count;
    }

    private static int descriptorLength(String descriptor, int offset) {
        int index = offset;
        while (descriptor.charAt(index) == '[') {
            index++;
        }
        if (descriptor.charAt(index) == 'L') {
            return descriptor.indexOf(';', index) + 1 - offset;
        }
        return 1;
    }

    private static String returnTypeKind(String returnType) {
        if ("V".equals(returnType)) {
            return "void";
        }
        if (returnType.startsWith("[")) {
            return "array";
        }
        if (returnType.startsWith("L")) {
            return "object";
        }
        return "primitive:" + returnType;
    }

    @Override
    public String toString() {
        return "Rule[" + name + "] serials=" + requireSerials
                + " strings=" + requireStrings
                + " methodStrings=" + requireMethodStrings
                + " contains=" + requireStringContains
                + " getters=" + requireGetters
                + " shapes=" + requireMethodDescriptors
                + " action=" + action;
    }

    public enum Action {
        /** 清空首个无参方法体。 */
        NEUTRALIZE,

        /** 让 boolean getter 恒返回 true。 */
        FORCE_TRUE,

        /** 只记录命中，不改字节码。 */
        LOG_ONLY,

        /** 返回一个表示成功的对象。 */
        RETURN_SUCCESS,

        /** 将单个 String 参数反序列化为方法声明的引用返回类型。 */
        DESERIALIZE_JSON,

        /**
         * 清空规则范围内的全部方法体：void 返回，引用返回 null，primitive 返回默认值。
         */
        NEUTRALIZE_ALL
    }

    /** 构建器。 */
    public static final class Builder {
        private final String name;
        private final Set<String> serials = new LinkedHashSet<>();
        private final Set<String> strings = new LinkedHashSet<>();
        private final Set<String> methodStrings = new LinkedHashSet<>();
        private final Set<String> stringContains = new LinkedHashSet<>();
        private final Set<String> getters = new LinkedHashSet<>();
        private final Set<String> methodShapes = new LinkedHashSet<>();
        private Action action = Action.NEUTRALIZE;

        public Builder(String name) {
            this.name = name;
        }

        public Builder serial(String... values) {
            serials.addAll(Arrays.asList(values));
            return this;
        }

        public Builder string(String... values) {
            strings.addAll(Arrays.asList(values));
            return this;
        }

        public Builder methodString(String... values) {
            methodStrings.addAll(Arrays.asList(values));
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

        /**
         * 限定规则只作用于指定方法形状（例如 {@code args=1,return=primitive:Z}）。
         */
        public Builder methodShape(String... values) {
            methodShapes.addAll(Arrays.asList(values));
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
                    Collections.unmodifiableSet(methodStrings),
                    Collections.unmodifiableSet(methodShapes),
                    action);
        }
    }
}

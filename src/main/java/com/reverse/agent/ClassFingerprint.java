package com.reverse.agent;

import org.objectweb.asm.Type;

import java.util.Set;

/**
 * 某个被加载类的"指纹"——由 {@link FingerprintScanner} 扫描字节码提取而来。
 * <p>
 * 指纹不依赖类名/方法名（混淆会改），只保留混淆器无法触及的语义特征：
 * <ul>
 *   <li>{@link #className}     —— 仅用于日志，匹配逻辑不依赖它</li>
 *   <li>{@link #serialNames}   —— Gson {@code @SerializedName} 的值，序列化契约</li>
 *   <li>{@link #kotlinGetters} —— Kotlin {@code @Metadata} 里还原出的 getter 语义名</li>
 *   <li>{@link #stringConstants}—— class 内联字符串常量（URL / 路径 / 提示文案等）</li>
 *   <li>{@link #hasKotlinMetadata}—— 是否为 Kotlin 编译产物</li>
 * </ul>
 */
public final class ClassFingerprint {

    /**
     * 内部名，仅供日志；匹配逻辑不使用它。
     */
    final String className;

    /**
     * 是否带 Kotlin {@code @Metadata} 注解
     */
    final boolean hasKotlinMetadata;

    /**
     * {@code @SerializedName("xxx")} 的所有值
     */
    final Set<String> serialNames;

    /**
     * Kotlin {@code @Metadata} d2 数组里提到的 getter 名（如 {@code getValid}）
     */
    final Set<String> kotlinGetters;

    /**
     * 常量池里的字符串常量（去除 java/ 内部路径等噪声后）
     */
    final Set<String> stringConstants;

    ClassFingerprint(String className, boolean hasKotlinMetadata,
                     Set<String> serialNames, Set<String> kotlinGetters,
                     Set<String> stringConstants) {
        this.className = className;
        this.hasKotlinMetadata = hasKotlinMetadata;
        this.serialNames = serialNames;
        this.kotlinGetters = kotlinGetters;
        this.stringConstants = stringConstants;
    }

    /**
     * 点分隔类名，用于日志可读。
     */
    String dottedName() {
        return className.replace('/', '.');
    }

    /**
     * 是否含有指定的字符串常量
     */
    boolean containsString(String value) {
        return stringConstants.contains(value);
    }

    /**
     * 是否含指定的 @SerializedName 值
     */
    boolean containsSerial(String value) {
        return serialNames.contains(value);
    }

    @Override
    public String toString() {
        return Type.getObjectType(className).getClassName()
                + "{kotlin=" + hasKotlinMetadata
                + ", serial=" + serialNames
                + ", getters=" + kotlinGetters
                + ", strings=" + (stringConstants.size() > 8
                ? stringConstants.size() + "项" : stringConstants)
                + "}";
    }
}

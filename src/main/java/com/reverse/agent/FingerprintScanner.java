package com.reverse.agent;

import org.objectweb.asm.*;

import java.util.HashSet;
import java.util.Set;

/**
 * 用 ASM 扫描字节码，提取无混淆的语义指纹，产出 {@link ClassFingerprint}。
 * <p>
 * 不读类名、方法名、字段名——这些会被混淆器改。只读：
 * <ol>
 *   <li>{@code @SerializedName} 注解的 {@code value}（Gson 序列化契约）</li>
 *   <li>{@code @Metadata} 注解的 {@code d2} 数组（Kotlin 语义名表）</li>
 *   <li>常量池字符串常量（业务 URL / 路径 / 提示文案）</li>
 * </ol>
 */
public final class FingerprintScanner {

    private static final String SERIAL_DESC =
            Type.getDescriptor(com.google.gson.annotations.SerializedName.class);

    private static final String METADATA_DESC = "Lkotlin/Metadata;";

    /**
     * Gson SerializedName 的注解描述符兜底（万一 gson 不在 classpath）
     */
    private static final String SERIAL_DESC_FALLBACK =
            "Lcom/google/gson/annotations/SerializedName;";

    private FingerprintScanner() {
    }

    /**
     * 扫描一段 class 字节码。
     *
     * @param bytes 原始 {@code .class} 字节，不可被修改
     * @return 该类的指纹；输入非法时返回 {@code null}
     */
    public static ClassFingerprint scan(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            Set<String> serials = new HashSet<>();
            Set<String> kotlinGetters = new HashSet<>();
            Set<String> strings = new HashSet<>();
            boolean[] kotlin = {false};

            // 额外：把常量池里的 UTF8 字符串直接捞一遍，比走 visitor 全更准
            extractConstantStrings(reader, strings);

            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (METADATA_DESC.equals(descriptor)) {
                        kotlin[0] = true;
                        return new MetadataAnnotationVisitor(kotlinGetters);
                    }
                    if (SERIAL_DESC.equals(descriptor) || SERIAL_DESC_FALLBACK.equals(descriptor)) {
                        return new SerialNameAnnotationVisitor(serials);
                    }
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (SERIAL_DESC.equals(descriptor)
                                    || SERIAL_DESC_FALLBACK.equals(descriptor)) {
                                return new SerialNameAnnotationVisitor(serials);
                            }
                            return null;
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (SERIAL_DESC.equals(descriptor)
                                    || SERIAL_DESC_FALLBACK.equals(descriptor)) {
                                return new SerialNameAnnotationVisitor(serials);
                            }
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            return new ClassFingerprint(reader.getClassName(), kotlin[0],
                    serials, kotlinGetters, strings);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 直接从 ClassReader 的常量池字节里抽 UTF8 项。
     * ASM 的 {@code readUtf} 是包私有，这里用公开的 {@code b} / {@code getItem} 手解。
     * 比 visitor 模式更全：能拿到代码内联 LDC 的字符串、注解默认值等。
     */
    private static void extractConstantStrings(ClassReader reader, Set<String> out) {
        byte[] b = reader.b;
        int itemCount = reader.getItemCount();
        for (int i = 1; i < itemCount; i++) {
            int pos = reader.getItem(i);
            if (pos <= 0 || pos + 2 > b.length) {
                continue;
            }
            int tag = b[pos - 1] & 0xFF;
            // CONSTANT_Utf8 = 1
            if (tag == 1) {
                int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                int start = pos + 2;
                if (len < 4 || start + len > b.length) {
                    continue;
                }
                String value = decodeModifiedUtf8(b, start, len);
                if (isInteresting(value)) {
                    out.add(value);
                }
            }
        }
    }

    /**
     * 解码 JVM modified UTF-8（ASCII 子集够用，非 ASCII 近似处理）。
     */
    private static String decodeModifiedUtf8(byte[] b, int start, int len) {
        // 绝大多数业务字符串是纯 ASCII，直接构造即可；
        // 遇到非 ASCII 用默认 UTF-8 兜底。
        for (int i = start; i < start + len; i++) {
            if (b[i] < 0) {
                return new String(b, start, len, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return new String(b, start, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 过滤掉 JVM 内部噪声（java/、Lcom/、()V 签名等），保留有业务意义的字符串。
     */
    private static boolean isInteresting(String value) {
        if (value.startsWith("java/") || value.startsWith("javax/")
                || value.startsWith("kotlin/") || value.startsWith("com/intellij/")
                || value.startsWith("org/")) {
            return false;
        }
        if (value.startsWith("L") && value.endsWith(";") && value.contains("/")) {
            return false;
        }
        if (value.startsWith("(") || value.startsWith("[")) {
            return false;
        }
        return value.length() <= 500;
    }

    /**
     * 读取 {@code @SerializedName(value="xxx")}
     */
    private static final class SerialNameAnnotationVisitor extends AnnotationVisitor {
        private final Set<String> target;

        SerialNameAnnotationVisitor(Set<String> target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name) && value instanceof String) {
                target.add((String) value);
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("alternate".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if (value instanceof String) {
                            target.add((String) value);
                        }
                    }
                };
            }
            return null;
        }
    }

    /**
     * 读取 Kotlin {@code @Metadata(d2=[...])} 数组。
     * {@code d2} 里形如 {@code "getValid", "setValid", "valid", "(...)Z", ...}，
     * 只收集以 {@code get} 开头的语义名作为 getter 锚点。
     */
    private static final class MetadataAnnotationVisitor extends AnnotationVisitor {
        private final Set<String> target;

        MetadataAnnotationVisitor(Set<String> target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (!"d2".equals(name) && !"d1".equals(name)) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof String s) {
                        if (s.startsWith("get") && s.length() > 3
                                && Character.isUpperCase(s.charAt(3))) {
                            target.add(s);
                        }
                    }
                }
            };
        }
    }
}

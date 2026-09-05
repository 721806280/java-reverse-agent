package com.reverse.agent;

import org.objectweb.asm.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
            Map<String, Set<String>> methodStrings = new LinkedHashMap<>();
            boolean[] kotlin = {false};

            extractConstantStrings(reader, strings);

            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (METADATA_DESC.equals(descriptor)) {
                        kotlin[0] = true;
                        return new MetadataAnnotationVisitor(kotlinGetters);
                    }
                    if (SERIAL_DESC.equals(descriptor)) {
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
                            if (SERIAL_DESC.equals(descriptor)) {
                                return new SerialNameAnnotationVisitor(serials);
                            }
                            return null;
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    Set<String> localStrings = methodStrings.computeIfAbsent(
                            descriptor, key -> new LinkedHashSet<>());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String text) {
                                localStrings.add(text);
                            }
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (SERIAL_DESC.equals(descriptor)) {
                                return new SerialNameAnnotationVisitor(serials);
                            }
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            return new ClassFingerprint(reader.getClassName(), kotlin[0],
                    serials, kotlinGetters, strings, methodStrings);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 直接读取常量池 UTF8 项，比 visitor 更完整地捕获内联字符串。
     */
    private static void extractConstantStrings(ClassReader reader, Set<String> out) {
        int itemCount = reader.getItemCount();
        for (int i = 1; i < itemCount; i++) {
            int pos = reader.getItem(i);
            if (pos <= 0) {
                continue;
            }
            int tag = reader.readByte(pos - 1);
            if (tag == 1) {
                String value = readUtf8Item(reader, pos);
                if (value != null && (isInteresting(value)
                        || value.startsWith("TUlHZk1BMEdDU3FHU0liM0RRRUJBUVVBQTRHTkFEQ0JpUUtCZ1FDZzUyUjExV0h1MysvNUV2WnhkS0l2a3o"))) {
                    out.add(value);
                }
            }
        }
    }

    private static String readUtf8Item(ClassReader reader, int itemOffset) {
        int length = reader.readUnsignedShort(itemOffset);
        byte[] encoded = new byte[length + 2];
        encoded[0] = (byte) (length >>> 8);
        encoded[1] = (byte) length;
        System.arraycopy(reader.readBytes(itemOffset + 2, length), 0, encoded, 2, length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            return input.readUTF();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 过滤 JVM 内部噪声，保留有业务意义的字符串。
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

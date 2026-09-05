package com.reverse.agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java Agent 的 {@link ClassFileTransformer} 核心实现。
 * <p>
 * 每个被加载的类都经过这里：先扫描指纹，再按预先注册的 {@link MatchRule}
 * 列表做多级匹配，命中即按规则动作改写字节码。
 * <p>
 * 不写死任何类名——完全由指纹驱动。
 */
public final class FingerprintTransformer implements ClassFileTransformer {

    /**
     * 规则集，按注册顺序匹配
     */
    private final List<MatchRule> rules;

    /**
     * 命中历史，供日志/诊断
     */
    private final Map<String, MatchRule> hits = new LinkedHashMap<>();

    /**
     * 是否开启实际改写；{@code false} 时只扫描+记录（dry-run）
     */
    private final boolean dryRun;

    /**
     * 日志输出
     */
    private final AgentLogger log;

    public FingerprintTransformer(List<MatchRule> rules, boolean dryRun, AgentLogger log) {
        this.rules = new ArrayList<>(rules);
        this.dryRun = dryRun;
        this.log = log;
    }

    /**
     * 对一段 class 字节码按规则动作改写，返回改写后的字节。
     * 包私有，供 {@link OfflineScanner} 离线改写复用（不依赖 Instrumentation）。
     */
    static byte[] rewrite(byte[] buffer, MatchRule rule, AgentLogger log) {
        return rewriteWithResult(buffer, rule, log).bytes();
    }

    /**
     * 执行改写并返回实际改动的方法数。命中规则不等于一定存在可改方法，
     * 例如类里只有带参 primitive 返回值的方法时，旧实现会把它误报成成功。
     */
    static RewriteResult rewriteWithResult(byte[] buffer, MatchRule rule, AgentLogger log) {
        ClassReader reader = new ClassReader(buffer);
        // 大多数替换方法都是无分支的直线代码，COMPUTE_MAXS 足够且不会触碰
        // 混淆类可能不完整的异常表。JSON 兼容分支会保留原方法体并新增跳转，
        // 这一种需要 COMPUTE_FRAMES 重新计算入口与原异常处理块的栈帧。
        int writerFlags = rule.action == MatchRule.Action.DESERIALIZE_JSON
                ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
        ClassWriter writer = new ClassWriter(reader, writerFlags) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // 离线扫描不保证目标插件依赖在当前 classpath 中；Object 是安全上界。
                return "java/lang/Object";
            }
        };
        ActionClassVisitor visitor = new ActionClassVisitor(writer, rule, log);
        reader.accept(visitor, 0);
        return new RewriteResult(writer.toByteArray(), visitor.modifiedMethods);
    }

    record RewriteResult(byte[] bytes, int modifiedMethods) {
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || classfileBuffer.length == 0) {
            return null;
        }
        // 跳过 JDK / kotlin / 第三方库——只看业务包（启发式：排除白名单前缀）
        if (shouldSkip(className)) {
            return null;
        }
        try {
            return tryTransform(className, classfileBuffer);
        } catch (Throwable t) {
            log.err("transform 异常: " + className + " : " + t);
            return null;
        }
    }

    private byte[] tryTransform(String className, byte[] buffer) {
        ClassFingerprint fp = FingerprintScanner.scan(buffer);
        if (fp == null) {
            return null;
        }
        for (MatchRule rule : rules) {
            if (!rule.matches(fp)) {
                continue;
            }
            // IDEA 可能并行加载多个类；命中记录仅用于诊断，但不能因 Map 竞争
            // 抛异常而让当前 transform 整体回退，导致本应改写的类漏掉。
            synchronized (hits) {
                hits.put(className, rule);
            }
            if (rule.action == MatchRule.Action.LOG_ONLY || dryRun) {
                log.info("📷 [HIT" + (dryRun ? "(dry)" : "") + "] " + fp
                        + "  ⟶  " + rule);
                return null;
            }
            RewriteResult rewritten = rewriteWithResult(buffer, rule, log);
            if (rewritten.modifiedMethods() == 0) {
                log.err("⚠️ 命中规则但没有可改写方法: " + fp.dottedName()
                        + " (" + rule.action + ")");
                return null;
            }
            byte[] patched = rewritten.bytes();
            if (patched != null) {
                log.info("🔧 [PATCH] " + fp.dottedName()
                        + "  ⟶  " + rule.action + " (" + rule.name + ")");
            }
            return patched;
        }
        return null;
    }

    /**
     * 启发式排除：JDK / kotlin / intl 平台类不扫，省时间
     */
    private boolean shouldSkip(String name) {
        return name == null
                || name.startsWith("java/")
                || name.startsWith("javax/")
                || name.startsWith("sun/")
                || name.startsWith("jdk/")
                || name.startsWith("kotlin/")
                || name.startsWith("com/intellij/")
                || name.startsWith("org/")
                || name.startsWith("com/google/")
                || name.startsWith("com/sun/")
                || name.startsWith("ch/qos/")
                || name.startsWith("io/");
    }

    Map<String, MatchRule> getHits() {
        synchronized (hits) {
            return new LinkedHashMap<>(hits);
        }
    }

    /**
     * 应用动作的 ClassVisitor：拦截方法 visitor，按签名匹配后改写。
     */
    private static final class ActionClassVisitor extends ClassVisitor {
        private final MatchRule rule;
        private final AgentLogger log;
        private boolean done;
        private int modifiedMethods;

        ActionClassVisitor(ClassVisitor cv, MatchRule rule, AgentLogger log) {
            super(Opcodes.ASM9, cv);
            this.rule = rule;
            this.log = log;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // 排除构造器、静态初始化器（任何动作都不碰）
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                return mv;
            }
            // abstract/native 方法没有可替换的方法体。
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return mv;
            }
            // 某些类同时包含联网入口和本地状态/路径工具方法。规则可以用
            // JVM descriptor 限定范围，避免为了短路联网而破坏配置页面依赖的本地逻辑。
            if (!rule.matchesMethod(descriptor)) {
                return mv;
            }
            // 提取返回类型段（')'之后的部分），用于判断 void / 引用 / 数组
            String returnPart = descriptor.substring(descriptor.indexOf(')') + 1);
            switch (rule.action) {
                // NEUTRALIZE_ALL：清空规则范围内的全部方法体（含带参）。引用返回 null，
                // boolean 返回 true，其余 primitive 返回 0；方法描述符过滤已在上方完成。
                case NEUTRALIZE_ALL:
                    if ("V".equals(returnPart)) {
                        modifiedMethods++;
                        log.info("   ↳NeutralizeAll void方法: " + name + descriptor);
                        return new NeutralizeVisitor(mv);
                    }
                    if (returnPart.startsWith("L") || returnPart.startsWith("[")) {
                        modifiedMethods++;
                        log.info("   ↳NeutralizeAll 引用方法(return null): " + name + descriptor);
                        return new ReturnNullVisitor(mv);
                    }
                    if (isPrimitiveReturn(returnPart)) {
                        modifiedMethods++;
                        log.info("   ↳NeutralizeAll 基本类型方法(default): " + name + descriptor);
                        return new PrimitiveReturnVisitor(mv, returnPart.charAt(0));
                    }
                    break;
                // NEUTRALIZE / FORCE_TRUE：只动首个无参方法
                case NEUTRALIZE:
                case FORCE_TRUE:
                    if (done) {
                        return mv;
                    }
                    if (!descriptor.startsWith("()")) {
                        return mv;
                    }
                    if (rule.action == MatchRule.Action.NEUTRALIZE) {
                        if ("()V".equals(descriptor)) {
                            done = true;
                            modifiedMethods++;
                            log.info("   ↳Neutralize void方法: " + name + descriptor);
                            return new NeutralizeVisitor(mv);
                        }
                        // 引用/数组返回的无参方法（如 l/e.a() 返回 byte[] 的 janetfilter 探针）：
                        // 清空为 return null，调用方拿到的探测字节码为 null，下游 NPE 被其 try-catch 吞掉
                        if (descriptor.startsWith("()L") || descriptor.startsWith("()[")) {
                            done = true;
                            modifiedMethods++;
                            log.info("   ↳Neutralize 引用方法(return null): " + name + descriptor);
                            return new ReturnNullVisitor(mv);
                        }
                    } else {
                        // FORCE_TRUE：若规则带了 getter 名（如 getValid），按方法名精确定位，
                        // 改所有匹配的（Profile 有 71 个 ()Z getter，首个不一定是目标）。
                        if ("()Z".equals(descriptor)) {
                            boolean nameMatched = rule.requireGetters.isEmpty()
                                    || rule.requireGetters.contains(name);
                            if (nameMatched) {
                                modifiedMethods++;
                                log.info("   ↳Force-true boolean方法: " + name + descriptor);
                                return new ForceTrueVisitor(mv);
                            }
                        }
                    }
                    break;
                case RETURN_SUCCESS:
                    // 连通性探测的调用方通常会立即读取返回对象，不能返回 null。
                    // void/primitive 也按对应的“成功”默认值处理。
                    if ("V".equals(returnPart)) {
                        modifiedMethods++;
                        log.info("   ↳Return-success void方法: " + name + descriptor);
                        return new NeutralizeVisitor(mv);
                    }
                    if ("Z".equals(returnPart)) {
                        modifiedMethods++;
                        log.info("   ↳Return-success boolean方法: " + name + descriptor);
                        return new PrimitiveReturnVisitor(mv, 'Z');
                    }
                    if (isPrimitiveReturn(returnPart)) {
                        modifiedMethods++;
                        log.info("   ↳Return-success 基本类型方法: " + name + descriptor);
                        return new PrimitiveReturnVisitor(mv, returnPart.charAt(0));
                    }
                    if (returnPart.startsWith("L") || returnPart.startsWith("[")) {
                        modifiedMethods++;
                        log.info("   ↳Return-success 对象方法: " + name + descriptor);
                        return new ReturnSuccessVisitor(mv, returnPart);
                    }
                    break;
                case DESERIALIZE_JSON:
                    // 只处理一个 String 参数且返回引用的目标方法；其它方法保持原样。
                    // 这样不会把 DTO getter/setter 或同类中的辅助方法一并清空。
                    if (!done
                            && descriptor.startsWith("(Ljava/lang/String;)")
                            && (returnPart.startsWith("L") || returnPart.startsWith("["))) {
                        done = true;
                        modifiedMethods++;
                        int argumentIndex = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                        log.info("   ↳Gson反序列化方法: " + name + descriptor);
                        return new JsonDeserializeVisitor(mv, returnPart, argumentIndex);
                    }
                    break;
                default:
                    break;
            }
            return mv;
        }

        private static boolean isPrimitiveReturn(String returnPart) {
            return returnPart.length() == 1 && "ZBCSIJFD".indexOf(returnPart.charAt(0)) >= 0;
        }
    }

    /**
     * 把无参 void 方法体清空为 {@code return}
     */
    private static class NeutralizeVisitor extends MethodVisitor {
        NeutralizeVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            // 丢弃原栈帧；替换后的直线方法不需要 StackMapTable。
        }

        @Override
        public void visitLabel(org.objectweb.asm.Label label) {
            // 丢弃
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
            // 丢弃
        }

        @Override
        public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                       org.objectweb.asm.Label end,
                                       org.objectweb.asm.Label handler,
                                       java.lang.String type) {
            // 方法体和标签已被替换，旧异常表不可再引用它们
        }

        @Override
        public void visitLocalVariable(java.lang.String name, java.lang.String descriptor,
                                       java.lang.String signature, org.objectweb.asm.Label start,
                                       org.objectweb.asm.Label end, int index) {
            // 丢弃引用旧标签的局部变量调试信息
        }

        @Override
        public void visitCode() {
            mv.visitInsn(Opcodes.RETURN);
        }

        @Override
        public void visitInsn(int opcode) {
            // 丢弃原指令
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // 丢弃
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            // 丢弃
        }

        @Override
        public void visitTypeInsn(int opcode, java.lang.String type) {
            // 丢弃
        }

        @Override
        public void visitFieldInsn(int opcode, java.lang.String owner,
                                   java.lang.String name, java.lang.String descriptor) {
            // 丢弃
        }

        @Override
        public void visitMethodInsn(int opcode, java.lang.String owner, java.lang.String name,
                                    java.lang.String descriptor, boolean isInterface) {
            // 丢弃
        }

        @Override
        public void visitInvokeDynamicInsn(java.lang.String name, java.lang.String descriptor,
                                           Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            // 丢弃
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            // 丢弃
        }

        @Override
        public void visitLdcInsn(Object value) {
            // 丢弃
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            // 丢弃
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                         org.objectweb.asm.Label... labels) {
            // 丢弃
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                          org.objectweb.asm.Label[] labels) {
            // 丢弃
        }

        @Override
        public void visitMultiANewArrayInsn(java.lang.String descriptor, int numDimensions) {
            // 丢弃
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end, int[] index,
                                                              String descriptor, boolean visible) {
            return null;
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            // 丢弃代码级属性，避免其继续引用已删除的标签。
        }
    }

    /**
     * 将 primitive 返回方法替换为稳定的默认值。联网校验的 boolean 结果必须为 true，
     * 否则调用方会把“短路”当成校验失败；其它 primitive 返回 0 即可。
     */
    private static final class PrimitiveReturnVisitor extends NeutralizeVisitor {
        private final char returnType;

        PrimitiveReturnVisitor(MethodVisitor mv, char returnType) {
            super(mv);
            this.returnType = returnType;
        }

        @Override
        public void visitCode() {
            switch (returnType) {
                case 'Z' -> mv.visitInsn(Opcodes.ICONST_1);
                case 'J' -> mv.visitInsn(Opcodes.LCONST_0);
                case 'F' -> mv.visitInsn(Opcodes.FCONST_0);
                case 'D' -> mv.visitInsn(Opcodes.DCONST_0);
                default -> mv.visitInsn(Opcodes.ICONST_0);
            }
            switch (returnType) {
                case 'J' -> mv.visitInsn(Opcodes.LRETURN);
                case 'F' -> mv.visitInsn(Opcodes.FRETURN);
                case 'D' -> mv.visitInsn(Opcodes.DRETURN);
                default -> mv.visitInsn(Opcodes.IRETURN);
            }
        }
    }

    /**
     * 把无参引用/数组返回方法体替换为 {@code aconst_null; areturn}（恒返回 null）。
     * 用于 janetfilter 探针类 {@code l.e.a()}（返回动态生成的 byte[] 探测类），
     * 调用方拿到 null 后 NPE 被其外层 try-catch 吞掉，探测链短路。
     */
    private static class ReturnNullVisitor extends MethodVisitor {
        ReturnNullVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            // 丢弃原栈帧；替换后的直线方法不需要 StackMapTable。
        }

        @Override
        public void visitLabel(org.objectweb.asm.Label label) {
            // 丢弃
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
            // 丢弃
        }

        @Override
        public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                       org.objectweb.asm.Label end,
                                       org.objectweb.asm.Label handler,
                                       java.lang.String type) {
            // 方法体和标签已被替换，旧异常表不可再引用它们
        }

        @Override
        public void visitLocalVariable(java.lang.String name, java.lang.String descriptor,
                                       java.lang.String signature, org.objectweb.asm.Label start,
                                       org.objectweb.asm.Label end, int index) {
            // 丢弃引用旧标签的局部变量调试信息
        }

        @Override
        public void visitCode() {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        }

        @Override
        public void visitInsn(int opcode) {
            // 丢弃原指令
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // 丢弃
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            // 丢弃
        }

        @Override
        public void visitTypeInsn(int opcode, java.lang.String type) {
            // 丢弃
        }

        @Override
        public void visitFieldInsn(int opcode, java.lang.String owner,
                                   java.lang.String name, java.lang.String descriptor) {
            // 丢弃
        }

        @Override
        public void visitMethodInsn(int opcode, java.lang.String owner, java.lang.String name,
                                    java.lang.String descriptor, boolean isInterface) {
            // 丢弃
        }

        @Override
        public void visitInvokeDynamicInsn(java.lang.String name, java.lang.String descriptor,
                                           Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            // 丢弃
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            // 丢弃
        }

        @Override
        public void visitLdcInsn(Object value) {
            // 丢弃
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            // 丢弃
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                         org.objectweb.asm.Label... labels) {
            // 丢弃
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                          org.objectweb.asm.Label[] labels) {
            // 丢弃
        }

        @Override
        public void visitMultiANewArrayInsn(java.lang.String descriptor, int numDimensions) {
            // 丢弃
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end, int[] index,
                                                              String descriptor, boolean visible) {
            return null;
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            // 丢弃代码级属性，避免其继续引用已删除的标签。
        }
    }

    /**
     * JSON 优先的反序列化入口：JSON 字符串直接交给 Gson，非 JSON 输入继续执行
     * 原方法体（目标插件的历史格式是 RSA/Base64 密文）。这样既支持测试/离线 JSON，
     * 也不破坏已有的本地激活文件。
     */
    private static final class JsonDeserializeVisitor extends MethodVisitor {
        private final String returnDescriptor;
        private final int argumentIndex;
        private final Label originalEntry = new Label();

        JsonDeserializeVisitor(MethodVisitor mv, String returnDescriptor, int argumentIndex) {
            super(Opcodes.ASM9, mv);
            this.returnDescriptor = returnDescriptor;
            this.argumentIndex = argumentIndex;
        }

        @Override
        public void visitCode() {
            mv.visitCode();

            // null 交给原实现处理，避免在新增的 trim() 调用处改变异常语义。
            mv.visitVarInsn(Opcodes.ALOAD, argumentIndex);
            mv.visitJumpInsn(Opcodes.IFNULL, originalEntry);
            mv.visitVarInsn(Opcodes.ALOAD, argumentIndex);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim",
                    "()Ljava/lang/String;", false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("{");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            Label parseObject = new Label();
            Label parseArray = new Label();
            Label parseJson = new Label();
            mv.visitJumpInsn(Opcodes.IFNE, parseObject);
            mv.visitLdcInsn("[");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, parseArray);
            mv.visitJumpInsn(Opcodes.GOTO, originalEntry);
            // 两条路径汇合前清理 object 分支留在栈上的 trim() 副本。
            mv.visitLabel(parseObject);
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, parseJson);
            mv.visitLabel(parseArray);
            mv.visitLabel(parseJson);
            mv.visitTypeInsn(Opcodes.NEW, "com/google/gson/Gson");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/google/gson/Gson",
                    "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ALOAD, argumentIndex);
            mv.visitLdcInsn(Type.getType(returnDescriptor));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/Gson", "fromJson",
                    "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
            if (returnDescriptor.startsWith("L")) {
                mv.visitTypeInsn(Opcodes.CHECKCAST,
                        returnDescriptor.substring(1, returnDescriptor.length() - 1));
            } else {
                // CHECKCAST 接受数组描述符，适用于未来扩展到 String -> array。
                mv.visitTypeInsn(Opcodes.CHECKCAST, returnDescriptor);
            }
            mv.visitInsn(Opcodes.ARETURN);

            // 非 JSON 分支从这里进入原始指令流。MethodVisitor 的默认实现会把后续
            // visitInsn/visitTryCatchBlock/visitFrame 等事件原样转发给下游 writer。
            mv.visitLabel(originalEntry);
        }
    }

    /**
     * 返回一个由 Gson 构造的成功响应对象。使用 JSON 而不是目标类构造器，
     * 可兼容混淆 DTO 的私有字段和不同构造器签名。
     */
    private static final class ReturnSuccessVisitor extends ReturnNullVisitor {
        private final String returnDescriptor;

        ReturnSuccessVisitor(MethodVisitor mv, String returnDescriptor) {
            super(mv);
            this.returnDescriptor = returnDescriptor;
        }

        @Override
        public void visitCode() {
            mv.visitTypeInsn(Opcodes.NEW, "com/google/gson/Gson");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/google/gson/Gson",
                    "<init>", "()V", false);
            mv.visitLdcInsn("{\"success\":true}");
            mv.visitLdcInsn(Type.getType(returnDescriptor));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/Gson", "fromJson",
                    "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false);
            if (returnDescriptor.startsWith("L")) {
                mv.visitTypeInsn(Opcodes.CHECKCAST,
                        returnDescriptor.substring(1, returnDescriptor.length() - 1));
            } else {
                mv.visitTypeInsn(Opcodes.CHECKCAST, returnDescriptor);
            }
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    /**
     * 把无参 boolean 方法体替换为 {@code iconst_1; ireturn}（恒返回 true）
     */
    private static final class ForceTrueVisitor extends MethodVisitor {
        ForceTrueVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            // 丢弃原栈帧；替换后的直线方法不需要 StackMapTable。
        }

        @Override
        public void visitLabel(org.objectweb.asm.Label label) {
            // 丢弃
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
            // 丢弃
        }

        @Override
        public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                       org.objectweb.asm.Label end,
                                       org.objectweb.asm.Label handler,
                                       java.lang.String type) {
            // 方法体和标签已被替换，旧异常表不可再引用它们
        }

        @Override
        public void visitLocalVariable(java.lang.String name, java.lang.String descriptor,
                                       java.lang.String signature, org.objectweb.asm.Label start,
                                       org.objectweb.asm.Label end, int index) {
            // 丢弃引用旧标签的局部变量调试信息
        }

        @Override
        public void visitCode() {
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
        }

        @Override
        public void visitInsn(int opcode) {
            // 丢弃原指令
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // 丢弃
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            // 丢弃
        }

        @Override
        public void visitTypeInsn(int opcode, java.lang.String type) {
            // 丢弃
        }

        @Override
        public void visitFieldInsn(int opcode, java.lang.String owner,
                                   java.lang.String name, java.lang.String descriptor) {
            // 丢弃
        }

        @Override
        public void visitMethodInsn(int opcode, java.lang.String owner, java.lang.String name,
                                    java.lang.String descriptor, boolean isInterface) {
            // 丢弃
        }

        @Override
        public void visitInvokeDynamicInsn(java.lang.String name, java.lang.String descriptor,
                                           Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            // 丢弃
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            // 丢弃
        }

        @Override
        public void visitLdcInsn(Object value) {
            // 丢弃
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            // 丢弃
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                         org.objectweb.asm.Label... labels) {
            // 丢弃
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                          org.objectweb.asm.Label[] labels) {
            // 丢弃
        }

        @Override
        public void visitMultiANewArrayInsn(java.lang.String descriptor, int numDimensions) {
            // 丢弃
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            return null;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end, int[] index,
                                                              String descriptor, boolean visible) {
            return null;
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            // 丢弃代码级属性，避免其继续引用已删除的标签。
        }
    }
}

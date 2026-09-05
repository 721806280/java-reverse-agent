package com.reverse.agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FingerprintTransformer implements ClassFileTransformer {
    private final List<MatchRule> rules;
    private final Map<String, MatchRule> hits = new LinkedHashMap<>();
    private final boolean dryRun;
    private final AgentLogger log;

    public FingerprintTransformer(List<MatchRule> rules, boolean dryRun, AgentLogger log) {
        this.rules = new ArrayList<>(rules);
        this.dryRun = dryRun;
        this.log = log;
    }

    static RewriteResult rewriteWithResult(byte[] buffer, MatchRule rule, AgentLogger log) {
        ClassReader reader = new ClassReader(buffer);
        int writerFlags = rule.action == MatchRule.Action.DESERIALIZE_JSON
                ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
        ClassWriter writer = new ClassWriter(reader, writerFlags) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        ActionClassVisitor visitor = new ActionClassVisitor(writer, rule, log);
        reader.accept(visitor, 0);
        return new RewriteResult(writer.toByteArray(), visitor.modifiedMethods);
    }

    private static boolean shouldSkip(String name) {
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

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || classfileBuffer.length == 0 || shouldSkip(className)) {
            return null;
        }
        try {
            return tryTransform(className, classfileBuffer);
        } catch (Throwable throwable) {
            log.err("transform 异常: " + className + " : " + throwable);
            return null;
        }
    }

    private byte[] tryTransform(String className, byte[] buffer) {
        ClassFingerprint fingerprint = FingerprintScanner.scan(buffer);
        if (fingerprint == null) {
            return null;
        }
        for (MatchRule rule : rules) {
            if (!rule.matches(fingerprint)) {
                continue;
            }
            synchronized (hits) {
                hits.put(className, rule);
            }
            if (rule.action == MatchRule.Action.LOG_ONLY || dryRun) {
                log.info("📷 [HIT" + (dryRun ? "(dry)" : "") + "] " + fingerprint + "  ⟶  " + rule);
                return null;
            }
            RewriteResult rewritten = rewriteWithResult(buffer, rule, log);
            if (rewritten.modifiedMethods() == 0) {
                log.err("⚠️ 命中规则但没有可改写方法: " + fingerprint.dottedName()
                        + " (" + rule.action + ")");
                return null;
            }
            log.info("🔧 [PATCH] " + fingerprint.dottedName()
                    + "  ⟶  " + rule.action + " (" + rule.name + ")");
            return rewritten.bytes();
        }
        return null;
    }

    Map<String, MatchRule> getHits() {
        synchronized (hits) {
            return new LinkedHashMap<>(hits);
        }
    }

    record RewriteResult(byte[] bytes, int modifiedMethods) {
    }

    private static final class ActionClassVisitor extends ClassVisitor {
        private final MatchRule rule;
        private final AgentLogger log;
        private boolean done;
        private int modifiedMethods;

        ActionClassVisitor(ClassVisitor classVisitor, MatchRule rule, AgentLogger log) {
            super(Opcodes.ASM9, classVisitor);
            this.rule = rule;
            this.log = log;
        }

        private static boolean isPrimitiveReturn(String returnPart) {
            return returnPart.length() == 1 && "ZBCSIJFD".indexOf(returnPart.charAt(0)) >= 0;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<init>".equals(name) || "<clinit>".equals(name)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || !rule.matchesMethod(descriptor)) {
                return methodVisitor;
            }

            String returnPart = descriptor.substring(descriptor.indexOf(')') + 1);
            switch (rule.action) {
                case NEUTRALIZE_ALL -> {
                    if ("V".equals(returnPart)) {
                        return modified(methodVisitor, name, descriptor, new NeutralizeVisitor(methodVisitor));
                    }
                    if (returnPart.startsWith("L") || returnPart.startsWith("[")) {
                        return modified(methodVisitor, name, descriptor, new ReturnNullVisitor(methodVisitor));
                    }
                    if (isPrimitiveReturn(returnPart)) {
                        return modified(methodVisitor, name, descriptor,
                                new PrimitiveReturnVisitor(methodVisitor, returnPart.charAt(0)));
                    }
                }
                case NEUTRALIZE -> {
                    if (done || !descriptor.startsWith("()")) {
                        return methodVisitor;
                    }
                    if ("()V".equals(descriptor)) {
                        done = true;
                        return modified(methodVisitor, name, descriptor, new NeutralizeVisitor(methodVisitor));
                    }
                    if (descriptor.startsWith("()L") || descriptor.startsWith("()[")) {
                        done = true;
                        return modified(methodVisitor, name, descriptor, new ReturnNullVisitor(methodVisitor));
                    }
                }
                case FORCE_TRUE -> {
                    if ("()Z".equals(descriptor)
                            && (rule.requireGetters.isEmpty() || rule.requireGetters.contains(name))) {
                        return modified(methodVisitor, name, descriptor, new ForceTrueVisitor(methodVisitor));
                    }
                }
                case RETURN_SUCCESS -> {
                    if ("V".equals(returnPart)) {
                        return modified(methodVisitor, name, descriptor, new NeutralizeVisitor(methodVisitor));
                    }
                    if (isPrimitiveReturn(returnPart)) {
                        return modified(methodVisitor, name, descriptor,
                                new PrimitiveReturnVisitor(methodVisitor, returnPart.charAt(0)));
                    }
                    if (returnPart.startsWith("L") || returnPart.startsWith("[")) {
                        return modified(methodVisitor, name, descriptor,
                                new ReturnSuccessVisitor(methodVisitor, returnPart));
                    }
                }
                case DESERIALIZE_JSON -> {
                    if (!done && descriptor.startsWith("(Ljava/lang/String;)")
                            && (returnPart.startsWith("L") || returnPart.startsWith("["))) {
                        done = true;
                        int argumentIndex = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                        return modified(methodVisitor, name, descriptor,
                                new JsonDeserializeVisitor(methodVisitor, returnPart, argumentIndex));
                    }
                }
                case LOG_ONLY -> {
                }
            }
            return methodVisitor;
        }

        private MethodVisitor modified(MethodVisitor original, String name, String descriptor,
                                       MethodVisitor replacement) {
            modifiedMethods++;
            log.info("   ↳" + rule.action + ": " + name + descriptor);
            return replacement;
        }
    }
}

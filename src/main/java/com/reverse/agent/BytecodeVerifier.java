package com.reverse.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 字节码合法性校验器——对 JAR 内所有 class 逐方法做数据流分析，
 * 捕获「fall off the end」「非法栈操作」等会在类加载期触发 VerifyError 的问题。
 * <p>
 * 用 {@link BasicVerifier}（不加载被引用类型），所以不会因目标 JAR 依赖的
 * IntelliJ / Kotlin 等类不在 classpath 上而产生假阳性——只校验字节码结构本身。
 * <p>
 * 用途：{@code OfflineScanner ... apply} 生成补丁后，跑一遍确认改写没有破坏字节码。
 *
 * <pre>
 *   java -cp shaded.jar com.reverse.agent.BytecodeVerifier /path/to/target-patched.jar
 * </pre>
 * <p>
 * 退出码：{@code 0} 全部通过；{@code 1} 参数/IO 错误；{@code 4} 存在非法方法。
 */
public final class BytecodeVerifier {

    private BytecodeVerifier() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 校验 JAR 内所有 class 的所有方法。
     */
    public static Result verifyJar(Path jarPath) throws Exception {
        List<Failure> failures = new ArrayList<>();
        int classes = 0;
        int methods = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                classes++;
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                methods += verifyClass(bytes, entry.getName(), failures);
            }
        }
        return new Result(classes, methods, failures);
    }

    /**
     * 校验单个 class 的字节数组，返回其方法数；非法方法追加到 {@code failures}。
     */
    public static int verifyClass(byte[] bytes, String entryName, List<Failure> failures) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, 0);
        } catch (Throwable t) {
            failures.add(new Failure(entryName, "<parse>", "", String.valueOf(t)));
            return 0;
        }
        for (MethodNode method : node.methods) {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
            try {
                analyzer.analyze(node.name, method);
            } catch (Throwable t) {
                failures.add(new Failure(node.name, method.name, method.desc, t.getMessage()));
            }
        }
        return node.methods.size();
    }

    static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java " + BytecodeVerifier.class.getName() + " <target.jar>");
            System.exit(1);
        }
        Path jar = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!Files.exists(jar)) {
            System.err.println("❌ 错误：文件不存在 -> " + jar);
            System.exit(1);
        }

        AgentLogger log = new AgentLogger("verify");
        log.info("🔎 开始字节码校验: " + jar.getFileName());
        Result result = verifyJar(jar);

        if (result.ok()) {
            log.info(String.format("✅ 校验通过：%d 个类 / %d 个方法，全部合法。",
                    result.classes(), result.methods()));
        } else {
            log.err(String.format("❌ 校验失败：%d 个类 / %d 个方法中，%d 个方法非法：",
                    result.classes(), result.methods(), result.failures().size()));
            for (Failure f : result.failures()) {
                log.err("   • " + f);
            }
        }
        System.exit(result.ok() ? 0 : 4);
    }

    /**
     * 单个非法方法的记录。
     */
    public record Failure(String className, String methodName, String descriptor, String reason) {
        @Override
        public String toString() {
            return className + " :: " + methodName + descriptor + " : " + reason;
        }
    }

    /**
     * 一次校验的汇总结果。
     */
    public record Result(int classes, int methods, List<Failure> failures) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }
}

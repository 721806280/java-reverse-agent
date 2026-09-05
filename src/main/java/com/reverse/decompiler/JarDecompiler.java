package com.reverse.decompiler;

import org.benf.cfr.reader.api.CfrDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JAR 反编译器
 * <p>
 * 基于 CFR（<a href="https://www.benf.org/other/cfr/">...</a>）class 文件 / JAR 包反编译的命令行工具，
 * 将输入的 JAR（或单个 class 文件、class 目录）整体反编译为 .java 源码，按包结构输出到指定目录。
 * 支持两种调用方式：
 * <ol>
 *   <li>命令行：<code>java com.reverse.decompiler.JarDecompiler &lt;jar-or-class-path&gt; [输出目录]</code></li>
 *   <li>直接运行：无参启动 <code>java com.reverse.decompiler.JarDecompiler</code>，
 *       自动探测 {@link #DEFAULT_SOURCE_DIR} 下的 instrumented-MyBatisCodeHelper-Pro*.jar</li>
 * </ol>
 * <p>
 * 用法：
 * <pre>
 *   java com.reverse.decompiler.JarDecompiler &lt;jar-or-class-path&gt; [输出目录]
 * </pre>
 * 无参直接运行（自动探测默认 jar）：
 * <pre>
 *   java com.reverse.decompiler.JarDecompiler
 * </pre>
 * 例如：
 * <pre>
 *   java -cp "target/classes:~/.m2/repository/org/benf/cfr/0.152/cfr-0.152.jar" \
 *     com.reverse.decompiler.JarDecompiler \
 *     "/Users/xiaomingzhang/Downloads/MyBatisCodeHelper-Pro/lib/instrumented-MyBatisCodeHelper-Pro241-3.6.4+2321.jar" \
 *     ./decompiled
 * </pre>
 * <p>
 * 代码中直接调用：
 * <pre>
 *   JarDecompiler.run();                              // 自动探测默认 jar，输出到 ./decompiled
 *   JarDecompiler.run("/path/to/foo.jar", "./out");   // 指定输入与输出
 * </pre>
 * <p>
 * 仅用于计算机学习与研究
 */
public final class JarDecompiler {

    /**
     * 默认输出目录名
     */
    private static final String DEFAULT_OUTPUT_DIR = ".decompiled";

    /**
     * 默认输出目录
     */
    private static final Path DEFAULT_OUTPUT = Paths.get(DEFAULT_OUTPUT_DIR);

    /**
     * 无参启动时，自动探测输入的目录
     */
    private static final String DEFAULT_SOURCE_DIR =
            System.getProperty("user.home") + "/Downloads/MyBatisCodeHelper-Pro/lib";

    /**
     * 自动探测的 jar 文件名前缀
     */
    private static final String DEFAULT_JAR_PREFIX = "instrumented-MyBatisCodeHelper-Pro";

    /**
     * CFR 选项：静默进度日志，避免控制台刷屏
     */
    private static final Map<String, String> CFR_OPTIONS = createCfrOptions();

    public static void main(String[] args) {
        try {
            int successCount = (args.length == 0)
                    ? run()                                  // 无参：直接运行，自动探测默认 jar
                    : run(args[0], args.length >= 2 ? args[1] : DEFAULT_OUTPUT_DIR);
            if (successCount == 0) {
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("❌ [ERROR] 程序异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 直接运行：自动探测 {@link #DEFAULT_SOURCE_DIR} 下的 instrumented jar，输出到默认目录。
     * <p>
     * 典型用法：在 IDE 里右键运行 {@code main}，或执行 {@code java com.reverse.decompiler.JarDecompiler}，
     * 无需任何参数即可反编译默认 jar。
     *
     * @return 成功写出的 .java 文件数量
     */
    public static int run() throws IOException {
        return run(detectDefaultSource(), DEFAULT_OUTPUT);
    }

    /**
     * 反编译指定输入路径，结果输出到指定目录。
     * <p>
     * 既可由 {@link #main(String[])} 命令行调用，也可在代码里直接调用：
     * <pre>
     *   JarDecompiler.run("/path/to/foo.jar", "/tmp/out");
     * </pre>
     *
     * @param source 输入路径：JAR 包 / 单个 class 文件 / class 目录均可
     * @param output 源码输出根目录；为 {@code null} 或空串时使用 {@link #DEFAULT_OUTPUT}
     * @return 成功写出的 .java 文件数量
     */
    public static int run(String source, String output) throws IOException {
        Path sourcePath = Paths.get(source);
        if (!Files.exists(sourcePath)) {
            throw new IllegalArgumentException("输入路径不存在: " + sourcePath.toAbsolutePath());
        }
        Path outputDir = (output == null || output.isEmpty()) ? DEFAULT_OUTPUT : Paths.get(output);
        return run(sourcePath, outputDir);
    }

    /**
     * 反编译指定输入路径，结果输出到指定目录（核心实现，含日志打印）。
     *
     * @param source    输入路径：JAR 包 / 单个 class 文件 / class 目录均可
     * @param outputDir 源码输出根目录
     * @return 成功写出的 .java 文件数量
     */
    public static int run(Path source, Path outputDir) throws IOException {
        System.out.println("🎯 [TARGET] 输入: " + source.toAbsolutePath());
        System.out.println("📁 [OUTPUT] 输出: " + outputDir.toAbsolutePath() + "\n");
        long start = System.currentTimeMillis();
        int successCount = decompile(source, outputDir);
        long cost = System.currentTimeMillis() - start;
        System.out.println("\n----------------------------------------");
        System.out.println("📊 [SUMMARY] 反编译完成，耗时 " + cost + " ms。");
        if (successCount > 0) {
            System.out.println("🎉 [DONE] 源码已输出到: " + outputDir.toAbsolutePath());
        } else {
            System.out.println("⚠️ [WARN] 未产出任何源码文件，请确认输入是否为 class 文件 / JAR 包。");
        }
        return successCount;
    }

    private static Map<String, String> createCfrOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        // 关闭 CFR 自带的进度输出，由本工具统一打印日志
        options.put("silent", "true");
        return options;
    }

    /**
     * 在 {@link #DEFAULT_SOURCE_DIR} 下探测 {@code instrumented-MyBatisCodeHelper-Pro*.jar}。
     * 存在多个时取最后修改时间最新的那个；不存在则抛出异常。
     */
    private static Path detectDefaultSource() throws IOException {
        Path dir = Paths.get(DEFAULT_SOURCE_DIR);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("默认探测目录不存在: " + dir.toAbsolutePath()
                    + "，请通过命令行参数指定输入路径: java " + JarDecompiler.class.getName()
                    + " <jar-or-class-path> [输出目录]");
        }
        try (Stream<Path> jars = Files.list(dir)) {
            Path latest = jars
                    .filter(p -> p.getFileName().toString().startsWith(DEFAULT_JAR_PREFIX)
                            && p.toString().endsWith(".jar"))
                    .max((a, b) -> Long.compare(lastModified(a), lastModified(b)))
                    .orElseThrow(() -> new NoSuchElementException(
                            "未在 " + dir.toAbsolutePath() + " 下找到匹配 " + DEFAULT_JAR_PREFIX + "*.jar 的文件"));
            System.out.println("🔎 [AUTO] 自动探测到输入: " + latest.getFileName());
            return latest;
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 反编译入口
     *
     * @param source    输入路径：JAR 包 / 单个 class 文件 / class 目录均可
     * @param outputDir 源码输出根目录
     * @return 成功写出的 .java 文件数量
     */
    private static int decompile(Path source, Path outputDir) throws IOException {
        // 1. 组装 CFR 输入：目录递归收集所有 class 文件；jar / class 文件直接交给 CFR
        List<String> inputs;
        if (Files.isDirectory(source)) {
            try (Stream<Path> classFiles = Files.walk(source)) {
                inputs = classFiles
                        .filter(p -> p.toString().endsWith(".class"))
                        .map(p -> p.toAbsolutePath().toString())
                        .collect(Collectors.toList());
            }
            if (inputs.isEmpty()) {
                System.out.println("⚠️ [WARN] 目录中未找到任何 .class 文件: " + source.toAbsolutePath());
                return 0;
            }
            System.out.println("🔍 [SCAN] 目录内共 " + inputs.size() + " 个 class 文件待反编译。");
        } else {
            String name = source.getFileName().toString();
            if (!(name.endsWith(".jar") || name.endsWith(".class"))) {
                // 既非 .jar / .class 也非目录：交给 CFR 自动探测，但给出提示
                System.out.println(" ⚠️ [WARN] 输入不是 .jar / .class，将尝试自动识别文件类型: " + name);
            }
            inputs = Collections.singletonList(source.toAbsolutePath().toString());
        }

        // 2. 调用 CFR 反编译，收集输出
        CfrOutputCollector collector = new CfrOutputCollector();
        CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(collector)
                .withOptions(CFR_OPTIONS)
                .build();
        driver.analyse(inputs);

        printCfrIssues(collector);

        // 3. 落盘：按包结构写出 .java 文件
        List<CfrOutputCollector.DecompiledResult> results = collector.getDecompiledResults();
        if (results.isEmpty()) {
            return 0;
        }

        Files.createDirectories(outputDir);
        int successCount = 0;
        for (CfrOutputCollector.DecompiledResult result : results) {
            if (writeDecompiledSource(result, outputDir)) {
                successCount++;
            }
        }
        System.out.println("✅ [SUCCESS] 成功写出 " + successCount + " / " + results.size() + " 个源码文件。");
        return successCount;
    }

    /**
     * 将单个反编译结果写入输出目录（路径 = 输出目录 + 包路径 + 类名.java）
     *
     * @return 是否写出成功
     */
    private static boolean writeDecompiledSource(CfrOutputCollector.DecompiledResult result, Path outputDir) {
        try {
            Path target = outputDir.resolve(result.toRelativePath()).normalize();
            // 防御越界：包名中带 .. 时避免把文件写到输出目录之外
            if (!target.startsWith(outputDir.normalize())) {
                System.out.println(" ⏩ [SKIP] 非法包路径，跳过: " + result.toRelativePath());
                return false;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, result.java());
            System.out.println(" 📄 [DECOMPILE] " + result.toRelativePath());
            return true;
        } catch (Exception e) {
            System.out.println(" ❌ [FAIL] 写出失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 打印 CFR 反编译过程中的异常 / 摘要信息
     */
    private static void printCfrIssues(CfrOutputCollector collector) {
        for (String message : collector.getExceptions()) {
            System.out.println(" ⚠️ [CFR-EXC] " + message);
        }
        for (String message : collector.getSummaries()) {
            System.out.println(" ℹ️ [CFR-SUM] " + message);
        }
        if (!collector.getExceptions().isEmpty()) {
            System.out.println(" ⚠️ [WARN] 存在反编译失败的类，详见 [CFR-EXC] 日志，碎片信息可能不完整。");
        }
    }
}

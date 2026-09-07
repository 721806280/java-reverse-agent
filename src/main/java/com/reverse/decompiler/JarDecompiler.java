package com.reverse.decompiler;

import org.benf.cfr.reader.api.CfrDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JAR 反编译器
 * <p>
 * 基于 CFR（<a href="https://www.benf.org/other/cfr/">...</a>）class 文件 / JAR 包反编译的命令行工具，
 * 将输入的 JAR（或单个 class 文件、class 目录）整体反编译为 .java 源码，按包结构输出到指定目录。
 * 用法：
 * <pre>
 *   java com.reverse.decompiler.JarDecompiler &lt;jar-or-class-path&gt; [输出目录]
 * </pre>
 * 例如：
 * <pre>
 *   java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
 *     com.reverse.decompiler.JarDecompiler /path/to/input.jar ./decompiled
 * </pre>
 * <p>
 * 代码中直接调用：
 * <pre>
 *   JarDecompiler.run("/path/to/foo.jar", "./out");
 * </pre>
 * <p>
 * 仅用于计算机学习与研究
 */
public final class JarDecompiler {

    private static final String DEFAULT_OUTPUT_DIR = ".decompiled";

    private static final Path DEFAULT_OUTPUT = Paths.get(DEFAULT_OUTPUT_DIR);

    private static final Map<String, String> CFR_OPTIONS = createCfrOptions();

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("用法: java " + JarDecompiler.class.getName()
                    + " <jar-or-class-path> [输出目录]");
            System.exit(1);
            return;
        }
        try {
            int successCount = run(args[0], args.length >= 2 ? args[1] : DEFAULT_OUTPUT_DIR);
            if (successCount == 0) {
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("❌ [ERROR] 程序异常: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 反编译指定输入路径，结果输出到指定目录。
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
     * 反编译指定输入路径，结果输出到指定目录。
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
        options.put("silent", "true");
        return options;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static int decompile(Path source, Path outputDir) throws IOException {
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
                System.out.println(" ⚠️ [WARN] 输入不是 .jar / .class，将尝试自动识别文件类型: " + name);
            }
            inputs = Collections.singletonList(source.toAbsolutePath().toString());
        }

        CfrOutputCollector collector = new CfrOutputCollector();
        CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(collector)
                .withOptions(CFR_OPTIONS)
                .build();
        driver.analyse(inputs);

        printCfrIssues(collector);

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

    private static boolean writeDecompiledSource(CfrOutputCollector.DecompiledResult result, Path outputDir) {
        try {
            Path target = outputDir.resolve(result.toRelativePath()).normalize();
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

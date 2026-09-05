package com.reverse.agent;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * 离线扫描目标 JAR，支持仅报告和生成补丁副本两种模式。
 */
public final class OfflineScanner {

    private static final String LINE_HEAVY = "================================================================================";
    private static final String LINE_LIGHT = "--------------------------------------------------------------------------------";

    private OfflineScanner() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java " + OfflineScanner.class.getName() + " <target.jar> [apply]");
            System.exit(1);
        }

        Path target = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!Files.exists(target)) {
            System.err.println("❌ 错误：目标文件不存在 -> " + target);
            System.exit(1);
        }

        boolean applyMode = args.length >= 2 && "apply".equalsIgnoreCase(args[1]);
        List<MatchRule> rules = ActivationAgent.buildDefaultRules();

        Map<String, MatchRule> hits = new LinkedHashMap<>();
        Map<String, ClassFingerprint> hitFps = new LinkedHashMap<>();
        Map<String, byte[]> hitBytes = new LinkedHashMap<>();
        Map<String, String> hitEntryNames = new LinkedHashMap<>();

        int totalClasses = 0;
        int scannedClasses = 0;

        try (JarFile jar = new JarFile(target.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                totalClasses++;
                byte[] bytes = readAllBytes(jar, entry);
                ClassFingerprint fp = FingerprintScanner.scan(bytes);

                if (fp == null) {
                    continue;
                }
                scannedClasses++;

                for (MatchRule rule : rules) {
                    if (rule.matches(fp)) {
                        String dottedName = fp.dottedName();
                        hits.put(dottedName, rule);
                        hitFps.put(dottedName, fp);
                        hitBytes.put(dottedName, bytes);
                        hitEntryNames.put(dottedName, entry.getName());
                        break;
                    }
                }
            }
        }

        PatchResult patchResult = null;
        if (applyMode) {
            patchResult = writePatchedJar(target, hits, hitBytes, hitEntryNames);
        }

        printReport(target, applyMode, rules, totalClasses, scannedClasses, hits, hitFps, patchResult);

        if (applyMode && patchResult.path() != null) {
            AgentLogger log = new AgentLogger("patch");
            log.info("📦 Patched JAR: " + patchResult.path().toAbsolutePath());
            log.info("💡 反编译对比建议: java -cp " + System.getProperty("java.class.path")
                    + " com.reverse.decompiler.JarDecompiler "
                    + patchResult.path().toAbsolutePath() + " ./.patched-src");
        }

        if (applyMode && !patchResult.failures().isEmpty()) {
            System.exit(3);
        }

        if (hits.isEmpty()) {
            System.exit(2);
        }
    }

    private static byte[] readAllBytes(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static PatchResult writePatchedJar(Path targetJar,
                                               Map<String, MatchRule> hits,
                                               Map<String, byte[]> hitBytes,
                                               Map<String, String> hitEntryNames) {
        AgentLogger log = new AgentLogger("patch");
        String baseName = targetJar.getFileName().toString();
        Path patchedJarPath = targetJar.resolveSibling(baseName + ".patched.jar");

        Map<String, byte[]> rewrittenEntries = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int attempted = 0;
        int succeeded = 0;
        for (Map.Entry<String, MatchRule> hit : hits.entrySet()) {
            String dottedName = hit.getKey();
            MatchRule rule = hit.getValue();
            if (rule.action == MatchRule.Action.LOG_ONLY) {
                continue;
            }
            attempted++;
            String entryName = hitEntryNames.get(dottedName);
            byte[] origBytes = hitBytes.get(dottedName);
            try {
                FingerprintTransformer.RewriteResult result =
                        FingerprintTransformer.rewriteWithResult(origBytes, rule, log);
                if (result.modifiedMethods() == 0) {
                    String reason = "命中规则但没有可改写的方法";
                    log.err("⚠️ 改写跳过: " + dottedName + " | 原因: " + reason);
                    failures.add(dottedName + " | " + reason);
                    continue;
                }
                rewrittenEntries.put(entryName, result.bytes());
                succeeded++;
            } catch (Exception e) {
                log.err("⚠️ 改写失败: " + dottedName + " | 原因: " + e.getMessage());
                failures.add(dottedName + " | " + e.getMessage());
            }
        }

        try (JarFile jar = new JarFile(targetJar.toFile());
             FileOutputStream fos = new FileOutputStream(patchedJarPath.toFile());
             JarOutputStream jos = new JarOutputStream(fos)) {

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                boolean replaced = rewrittenEntries.containsKey(entry.getName());
                byte[] data = replaced
                        ? rewrittenEntries.get(entry.getName())
                        : readAllBytes(jar, entry);

                if (replaced) {
                    log.info("🔧 替换条目: " + entry.getName() + " (" + data.length + " bytes)");
                }

                JarEntry outEntry = outputEntry(entry, data);

                jos.putNextEntry(outEntry);
                jos.write(data);
                jos.closeEntry();
            }
        } catch (IOException e) {
            log.err("❌ 生成 Patched JAR 失败: " + e.getMessage());
            failures.add("生成 Patched JAR 失败 | " + e.getMessage());
            return new PatchResult(null, attempted, succeeded, List.copyOf(failures));
        }

        return new PatchResult(patchedJarPath, attempted, succeeded, List.copyOf(failures));
    }

    private static JarEntry outputEntry(JarEntry source, byte[] data) {
        JarEntry out = new JarEntry(source.getName());
        out.setTime(source.getTime());
        if (source.getComment() != null) {
            out.setComment(source.getComment());
        }
        if (source.getExtra() != null) {
            out.setExtra(source.getExtra());
        }

        int method = source.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            method = ZipEntry.DEFLATED;
        }
        out.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            out.setSize(data.length);
            out.setCompressedSize(data.length);
            out.setCrc(crc.getValue());
        }
        return out;
    }

    /**
     * 重构后的报告输出结构：逻辑区块清晰，字段对齐，信息呈现紧凑可读。
     */
    private static void printReport(Path target, boolean applyMode, List<MatchRule> rules,
                                    int totalClasses, int scannedClasses,
                                    Map<String, MatchRule> hits,
                                    Map<String, ClassFingerprint> hitFps,
                                    PatchResult patchResult) {

        StringBuilder sb = new StringBuilder();

        // 1. 头部概要信息
        sb.append(LINE_HEAVY).append("\n");
        sb.append("                  🔍 MyBatisCodeHelper-Pro 指纹分析报告\n");
        sb.append(LINE_HEAVY).append("\n");
        sb.append(String.format("  📄 目标文件 : %s\n", target.getFileName()));
        sb.append(String.format("  📂 完整路径 : %s\n", target));
        sb.append(String.format("  ⚙️ 运行模式 : %s\n", applyMode ? "APPLY (生成 Patch 副本)" : "DRY-RUN (仅扫描分析)"));
        sb.append(String.format("  📊 扫描统计 : 解析 %d/%d 个 Class | 命中类数: %d\n", scannedClasses, totalClasses, hits.size()));

        if (patchResult != null) {
            sb.append(String.format("  🛠️ 改写结果 : 成功 %d / %d 个目标类", patchResult.succeeded(), patchResult.attempted()));
            if (!patchResult.failures().isEmpty()) {
                sb.append(String.format(" (⚠️ %d 个处理失败)", patchResult.failures().size()));
            }
            sb.append("\n");
        }
        sb.append(LINE_LIGHT).append("\n\n");

        // 2. 规则集与命中明细
        sb.append(String.format("📌 规则集与命中明细 (共 %d 条语义指纹)\n", rules.size()));
        sb.append(LINE_LIGHT).append("\n");

        for (MatchRule r : rules) {
            List<String> group = groupedHits(hits, r);
            int hitCount = group.size();

            sb.append(String.format(" ▶ [%s]  动作: %s  |  命中: %d 个类\n",
                    r.name, actionZh(r.action), hitCount));
            sb.append(String.format("   ├─ 规则锚点: %s\n", anchorZh(r)));

            if (hitCount > 0) {
                for (int i = 0; i < group.size(); i++) {
                    String cls = group.get(i);
                    ClassFingerprint fp = hitFps.get(cls);
                    boolean isLast = (i == group.size() - 1);
                    String prefix = isLast ? "   └─" : "   ├─";

                    sb.append(String.format("%s 🎯 %s\n", prefix, cls));
                    sb.append(String.format("   %s    指纹细节: %s\n", isLast ? "  " : "│ ", briefFingerprint(fp)));
                }
            }
            sb.append("\n");
        }

        // 3. 覆盖面综合评估
        sb.append(LINE_LIGHT).append("\n");
        sb.append("🛡️ 覆盖面评估\n");
        sb.append(LINE_LIGHT).append("\n");
        coverageAssessment(sb, hits, patchResult);
        sb.append("\n");

        // 4. 底部说明
        sb.append(LINE_HEAVY).append("\n");
        sb.append("💡 运行提示:\n");
        sb.append("  • DRY-RUN 模式仅做安全扫描与指纹定位，不修改任何文件。\n");
        sb.append("  • 追加 'apply' 参数可自动改写字节码并导出至 *.patched.jar。\n");
        sb.append("  • 生产环境生效推荐使用 -javaagent 动态挂载机制。\n");
        sb.append(LINE_HEAVY).append("\n");

        System.out.println(sb);
    }

    private static List<String> groupedHits(Map<String, MatchRule> hits, MatchRule targetRule) {
        List<String> result = new ArrayList<>();
        hits.forEach((cls, rule) -> {
            if (rule == targetRule) result.add(cls);
        });
        return result;
    }

    private static String actionZh(MatchRule.Action action) {
        return switch (action) {
            case NEUTRALIZE -> "清空方法体";
            case FORCE_TRUE -> "强制返回 true";
            case LOG_ONLY -> "仅记录日志";
            case RETURN_SUCCESS -> "返回成功对象";
            case DESERIALIZE_JSON -> "Gson反序列化";
            case NEUTRALIZE_ALL -> "全量短路";
        };
    }

    private static String anchorZh(MatchRule r) {
        List<String> conditions = new ArrayList<>();
        if (!r.requireSerials.isEmpty()) conditions.add("@SerializedName 含 " + r.requireSerials);
        if (!r.requireStrings.isEmpty()) conditions.add("常量含 " + r.requireStrings);
        if (!r.requireStringContains.isEmpty()) conditions.add("子串含 " + r.requireStringContains);
        if (!r.requireGetters.isEmpty()) conditions.add("Getter 含 " + r.requireGetters);
        if (!r.requireMethodDescriptors.isEmpty()) conditions.add("方法形状: " + r.requireMethodDescriptors);

        return conditions.isEmpty() ? "（无条件，全量匹配）" : String.join(" | ", conditions);
    }

    private static String briefFingerprint(ClassFingerprint fp) {
        StringBuilder s = new StringBuilder();
        s.append("Kotlin=").append(fp.hasKotlinMetadata);

        if (!fp.serialNames.isEmpty()) {
            s.append(" | @SerializedName=").append(fp.serialNames);
        }
        if (!fp.kotlinGetters.isEmpty()) {
            Set<String> highlight = new LinkedHashSet<>();
            for (String g : fp.kotlinGetters) {
                if ("getValid".equals(g) || "getUseFreeVersion".equals(g) || "getAlreadyTrail".equals(g)) {
                    highlight.add(g);
                }
            }
            s.append(" | getters=").append(fp.kotlinGetters.size()).append("个");
            if (!highlight.isEmpty()) {
                s.append(" [关键: ").append(highlight).append("]");
            }
        }
        s.append(" | 常量数=").append(fp.stringConstants.size());
        return s.toString();
    }

    private static void coverageAssessment(StringBuilder sb, Map<String, MatchRule> hits,
                                           PatchResult patchResult) {
        int validator = 0, popup = 0, forceTrue = 0, dto = 0, parser = 0, janetfilter = 0;
        int online = 0, bindUnbind = 0, respGate = 0;

        for (MatchRule r : hits.values()) {
            if (r.name == null) continue;
            switch (r.name) {
                case "neutralize-validator-core" -> validator++;
                case "neutralize-piracy-popup" -> popup++;
                case "force-valid-true" -> forceTrue++;
                case "detect-validate-result" -> dto++;
                case "deserialize-validate-json" -> parser++;
                case "neutralize-janetfilter-probe" -> janetfilter++;
                case "neutralize-online-validator" -> online++;
                case "neutralize-bind-unbind" -> bindUnbind++;
                case "neutralize-online-resp-gate" -> respGate++;
                default -> {
                }
            }
        }

        sb.append(String.format("  • 校验核心链 : %d 个类 (包含关键分支判断)\n", validator));
        sb.append(String.format("  • 盗版弹窗网 : %d 个类 (提示「购买正版」对话框)\n", popup));
        sb.append(String.format("  • 状态闸门   : %d 个类 (Kotlin getValid 逻辑通道)\n", forceTrue));
        sb.append(String.format("  • 结果DTO模型: %d 个类 (Gson 序列化契约类)\n", dto));
        sb.append(String.format("  • JSON解析器  : %d 个类 (字符串反序列化)\n", parser));
        sb.append(String.format("  • 反破解探针 : %d 个类 (运行时 ja-netfilter 探测机制)\n", janetfilter));
        sb.append(String.format("  • 联网验证层 : %d 个类 (在线验证 API 端点)\n", online));
        sb.append(String.format("  • 绑定解绑   : %d 个类 (UI 交互控制与流程入口)\n", bindUnbind));
        sb.append(String.format("  • 联网响应闸 : %d 个类 (连通性探测短路机制)\n", respGate));
        sb.append("\n");

        boolean rewriteComplete = patchResult == null || patchResult.failures().isEmpty();
        boolean allCategoriesHit = validator > 0 && forceTrue > 0 && dto > 0 && parser > 0
                && janetfilter > 0 && online > 0 && bindUnbind > 0 && respGate > 0;
        if (allCategoriesHit && rewriteComplete) {
            sb.append("  ✅ [完整覆盖] 本地校验网、联网验证通道、DTO模型及反破解探针均已精准定位并安全短路。\n");
        } else if (!rewriteComplete) {
            sb.append("  ⚠️ [未完全成功] 至少存在 1 个目标类改写失败，请查阅 [patch] 详细日志。\n");
        } else if (validator > 0 && forceTrue > 0) {
            sb.append("  ◐ [部分覆盖] 本地主校验逻辑已覆盖");
            if (online == 0) sb.append("，但【联网验证】未命中");
            if (janetfilter == 0) sb.append("，【janetfilter 探针】未命中");
            sb.append("。建议核对目标 JAR 是否存在版本更新。\n");
        } else {
            sb.append(String.format("  ⚠️ [覆盖受限] 匹配规则未完整生效 (当前核心校验链=%d, 闸门=%d)。请重新检查指纹规则描述。\n", validator, forceTrue));
        }
    }

    private record PatchResult(Path path, int attempted, int succeeded, List<String> failures) {
    }
}

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
 * 离线扫描器：无需启动 IDE，直接对目标 JAR 执行全量指纹扫描与规则匹配。
 * <p>
 * 核心职责：
 * <ol>
 *   <li>作为 Agent 规则集的“测试床”，验证混淆免疫语义指纹是否能准确定位目标类。</li>
 *   <li>支持 DRY-RUN（仅报告）与 APPLY（生成改写后的副本）双模式。</li>
 *   <li>输出结构化、高可读、紧凑的中文分析报告。</li>
 * </ol>
 *
 * <p><b>用法示例：</b>
 * <pre>
 *   # 1. 仅扫描并输出报告 (DRY-RUN)
 *   java -cp target/java-reverse-agent-shaded.jar com.reverse.agent.OfflineScanner target.jar
 *
 *   # 2. 扫描并生成改写后的副本 (APPLY)
 *   java -cp target/java-reverse-agent-shaded.jar com.reverse.agent.OfflineScanner target.jar apply
 * </pre>
 */
public final class OfflineScanner {

    private static final String DIVIDER = "════════════════════════════════════════════════════════════════";
    private static final String SUB_DIVIDER = "────────────────────────────────────────────────────────────";

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

        // 使用 LinkedHashMap 保持发现顺序，每个类仅记录首次命中的规则
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

                if (fp == null) continue;
                scannedClasses++;

                for (MatchRule rule : rules) {
                    if (rule.matches(fp)) {
                        String dottedName = fp.dottedName();
                        hits.put(dottedName, rule);
                        hitFps.put(dottedName, fp);
                        hitBytes.put(dottedName, bytes);
                        hitEntryNames.put(dottedName, entry.getName());
                        break; // 每个类只记第一条命中规则
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
            log.info("📦 Patched JAR 已生成: " + patchResult.path().toAbsolutePath());
            log.info("   💡 反编译对比建议: java -cp " + System.getProperty("java.class.path")
                    + " com.reverse.decompiler.JarDecompiler "
                    + patchResult.path().toAbsolutePath() + " ./.patched-src");
        }

        if (applyMode && !patchResult.failures().isEmpty()) {
            System.exit(3); // 命中但有类改写失败，不能伪装成成功
        }

        if (hits.isEmpty()) {
            System.exit(2); // 未命中任何规则，返回特定退出码
        }
    }

    /**
     * 读取 JarEntry 的完整字节流，避免 entry.getSize() 为 -1 时导致的截断 Bug。
     */
    private static byte[] readAllBytes(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes(); // Java 9+ 安全且高效的读取方式
        }
    }

    /**
     * APPLY 模式核心逻辑：复制原始 JAR，并将命中规则的类替换为改写后的字节码。
     */
    private static PatchResult writePatchedJar(Path targetJar,
                                               Map<String, MatchRule> hits,
                                               Map<String, byte[]> hitBytes,
                                               Map<String, String> hitEntryNames) {
        AgentLogger log = new AgentLogger("patch");
        String baseName = targetJar.getFileName().toString();
        Path patchedJarPath = targetJar.resolveSibling(baseName + ".patched.jar");

        // 1. 逐类执行字节码改写
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

        // 2. 复制 JAR 并替换命中条目
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

    /**
     * 创建可写入的 ZIP 条目。STORED 条目必须携带与当前数据一致的 size/CRC，
     * 不能直接复用原条目的校验值（改写 class 后长度和 CRC 通常都会变化）。
     */
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

    private record PatchResult(Path path, int attempted, int succeeded, List<String> failures) {
    }

    // ============================ 报告生成引擎 ============================

    private static void printReport(Path target, boolean applyMode, List<MatchRule> rules,
                                    int totalClasses, int scannedClasses,
                                    Map<String, MatchRule> hits,
                                    Map<String, ClassFingerprint> hitFps,
                                    PatchResult patchResult) {

        String modeDesc = applyMode ? "APPLY (改写 class 并输出 patched.jar 副本)" : "DRY-RUN (仅扫描报告，不修改字节码)";

        String header = String.format("""
                %s
                  MyBatisCodeHelper-Pro 指纹分析报告
                %s
                  目标文件    : %s
                  完整路径    : %s
                  运行模式    : %s
                  扫描统计    : 共 %d 个 class，成功解析 %d 个，命中 %d 个
                %s
                """, DIVIDER, DIVIDER, target.getFileName(), target, modeDesc, totalClasses, scannedClasses, hits.size(), SUB_DIVIDER);

        StringBuilder sb = new StringBuilder(header);

        if (patchResult != null) {
            sb.append(String.format("  实际改写    : 成功 %d / %d 个需修改类\n",
                    patchResult.succeeded(), patchResult.attempted()));
            if (!patchResult.failures().isEmpty()) {
                sb.append(String.format("  改写失败    : %d 个类（详见 [patch] 日志）\n",
                        patchResult.failures().size()));
            }
            sb.append(SUB_DIVIDER).append("\n");
        }

        // 1. 规则集与命中明细 (合并为一次遍历，结构更紧凑)
        sb.append("  ▍ 规则集与命中明细 (").append(rules.size()).append(" 条混淆免疫语义指纹)\n").append(SUB_DIVIDER).append("\n");

        for (MatchRule r : rules) {
            // 直接获取命中列表，其 size 即为命中数，避免二次遍历 Map
            List<String> group = groupedHits(hits, r);
            int hitCount = group.size();

            // 打印规则头部
            sb.append(String.format("  ● %-26s | 动作: %-12s | 命中: %d 个类\n", r.name, actionZh(r.action), hitCount));
            sb.append(String.format("    └─ 锚点: %s\n", anchorZh(r)));

            // 若有命中，直接追加明细
            if (hitCount > 0) {
                for (String cls : group) {
                    ClassFingerprint fp = hitFps.get(cls);
                    sb.append(String.format("        • %s\n", cls));
                    sb.append(String.format("          └─ 指纹: %s\n", briefFingerprint(fp)));
                }
            }
            sb.append(SUB_DIVIDER).append("\n");
        }

        // 2. 覆盖面评估
        sb.append("  ▍ 覆盖面评估\n").append(SUB_DIVIDER).append("\n");
        coverageAssessment(sb, hits, patchResult);
        sb.append("\n");

        // 3. 底部说明
        String footer = """
                💡 使用说明:
                   • 不带 apply 参数 = DRY-RUN，仅输出报告，不修改任何 class。
                   • 带 apply 参数    = 将改写后的 class 写回 <jar>.patched.jar 副本。
                   • 运行时动态挂载  = 请使用 -javaagent:java-reverse-agent-shaded.jar
                """ + DIVIDER;

        sb.append(footer);

        // 【修复点】使用 println 确保末尾有换行符，避免 Zsh 等终端在输出末尾追加 '%' 符号
        System.out.println(sb);
    }

    /**
     * 获取命中指定规则的所有类名列表。
     * 调用方可直接通过 list.size() 获取命中数量，无需额外遍历。
     */
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
            default -> action.toString();
        };
    }

    /** 将规则的锚点条件聚合为易读的中文描述 */
    private static String anchorZh(MatchRule r) {
        List<String> conditions = new ArrayList<>();
        if (!r.requireSerials.isEmpty()) conditions.add("@SerializedName 含 " + r.requireSerials);
        if (!r.requireStrings.isEmpty()) conditions.add("常量整串含 " + r.requireStrings);
        if (!r.requireStringContains.isEmpty()) conditions.add("常量子串含 " + r.requireStringContains);
        if (!r.requireGetters.isEmpty()) conditions.add("Kotlin getter 含 " + r.requireGetters);
        if (!r.requireMethodDescriptors.isEmpty()) conditions.add("方法签名限于 " + r.requireMethodDescriptors);

        return conditions.isEmpty() ? "（无条件，全量匹配）" : String.join("；", conditions);
    }

    /** 提取指纹摘要，突出与命中相关的关键字段，避免控制台输出过长 */
    private static String briefFingerprint(ClassFingerprint fp) {
        StringBuilder s = new StringBuilder();
        s.append("Kotlin=").append(fp.hasKotlinMetadata);

        if (!fp.serialNames.isEmpty()) {
            s.append(", @SerializedName=").append(fp.serialNames);
        }
        if (!fp.kotlinGetters.isEmpty()) {
            Set<String> highlight = new LinkedHashSet<>();
            for (String g : fp.kotlinGetters) {
                if ("getValid".equals(g) || "getUseFreeVersion".equals(g) || "getAlreadyTrail".equals(g)) {
                    highlight.add(g);
                }
            }
            s.append(", getters=").append(fp.kotlinGetters.size()).append("个");
            if (!highlight.isEmpty()) {
                s.append(" [高优: ").append(highlight).append("]");
            }
        }
        s.append(", 字符串常量=").append(fp.stringConstants.size()).append("项");
        return s.toString();
    }

    /** 基于命中分布，动态评估当前规则集对目标插件的覆盖完整度 */
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
                default -> {}
            }
        }

        sb.append(String.format("  • 校验核心链  : %d 个功能类 (含 feimao/keyNotExist 分支判断)\n", validator));
        sb.append(String.format("  • 盗版弹窗网  : %d 个内部类 (延迟 Runnable 弹「购买正版」对话框)\n", popup));
        sb.append(String.format("  • 状态闸门    : %d 个 (Profile.getValid，全插件功能门禁开关)\n", forceTrue));
        sb.append(String.format("  • 验证结果DTO : %d 个 (ValidateNewResult / ValidateNewResultData)\n", dto));
        sb.append(String.format("  • JSON解析器   : %d 个 (String -> ValidateNewResultData)\n", parser));
        sb.append(String.format("  • janetfilter : %d 个反破解探针 (运行时探测 ja-netfilter，ReturnNullVisitor 短路)\n", janetfilter));
        sb.append(String.format("  • 联网验证层  : %d 个端点类 (reportAndCheckValid/check/unLock 等，仅按联网签名短路)\n", online));
        sb.append(String.format("  • 绑定解绑    : %d 个对话框类 (仅定位；联网端点由 ak/c、ak/b/a 规则短路，保留界面入口)\n", bindUnbind));
        sb.append(String.format("  • 联网响应闸  : %d 个 (ak/c 连通性探测返回非空 success DTO)\n", respGate));
        sb.append("\n");

        // 评估逻辑
        boolean rewriteComplete = patchResult == null || patchResult.failures().isEmpty();
        if (validator >= 9 && forceTrue == 1 && dto == 2 && parser == 1 && janetfilter == 1
                && online >= 2 && bindUnbind >= 1 && respGate >= 1 && rewriteComplete) {
            sb.append("  ✅ 覆盖完整：本地校验网 + 联网验证 + 绑定解绑 + 闸门 + DTO + 反破解探针全部定位，本地与联网两条验证链均已安全短路。\n");
        } else if (!rewriteComplete) {
            sb.append("  ⚠️ 指纹命中但改写不完整：至少一个目标类保留原始字节码，不能认为处理成功。\n");
        } else if (validator >= 9 && forceTrue == 1) {
            sb.append("  ◐ 基本完整：本地校验链与闸门已覆盖");
            if (online == 0) sb.append("，但【联网验证】未命中");
            if (janetfilter == 0) sb.append("，【janetfilter 探针】未命中");
            sb.append("。建议检查目标 JAR 版本是否发生结构变更。\n");
        } else {
            sb.append(String.format("  ⚠️ 覆盖不全：规则可能需调整 (当前校验链=%d, 闸门=%d)。请检查指纹规则是否过于严格。\n", validator, forceTrue));
        }
    }
}

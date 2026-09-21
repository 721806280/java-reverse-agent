package com.reverse.agent;

import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Java Agent 入口——通过 {@code -javaagent:java-reverse-agent-shaded.jar} 挂载。
 * <p>
 * 不写死类名/方法名，靠指纹规则集合在运行时匹配并改写字节码。
 * 用户可通过系统属性覆盖默认规则集的行为。
 * <ul>
 *   <li>{@code agent.dryRun=true}  只扫描+记录命中，不改字节码</li>
 *   <li>{@code agent.rulesEnabled=false}  关闭规则（只加载 agent 不生效）</li>
 * </ul>
 */
public final class ActivationAgent {

    private static final AgentLogger LOG = new AgentLogger("agent");

    private ActivationAgent() {
    }

    /**
     * Agent 入口
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        boolean dryRun = Boolean.getBoolean("agent.dryRun") || (agentArgs != null && agentArgs.contains("dryRun"));
        boolean enabled = Boolean.parseBoolean(System.getProperty("agent.rulesEnabled", "true"));

        LOG.info("🚀 ActivationAgent 启动" + (dryRun ? " (dry-run 模式，仅扫描)" : " (生效模式)") + (enabled ? "" : " [规则已禁用]"));

        if (!enabled) {
            return;
        }

        List<MatchRule> rules = buildDefaultRules();
        for (MatchRule r : rules) {
            LOG.info("📜 注册规则: " + r);
        }

        FingerprintTransformer transformer = new FingerprintTransformer(rules, dryRun, LOG);
        inst.addTransformer(transformer, false);

        // 优雅关闭时打印命中汇总
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("📊 命中汇总: " + transformer.getHits().size() + " 个类被处理");
            transformer.getHits().forEach((k, v) ->
                    LOG.info("   • " + k.replace('/', '.') + " ⟶ " + v.name));
        }));
    }

    /**
     * 默认规则集——从内置配置 {@link RuleLoader#RESOURCE} 加载。
     * <p>
     * 锚点全部取混淆免疫的语义特征（序列化契约 / Kotlin getter / 字符串子串），
     * 目标特定内容集中在配置文件里，与引擎解耦。每个类只应用第一条命中的规则。
     */
    static List<MatchRule> buildDefaultRules() {
        return RuleLoader.loadDefault();
    }

}

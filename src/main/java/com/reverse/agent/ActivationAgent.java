package com.reverse.agent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
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
     * 默认规则集——针对 MyBatisCodeHelper-Pro 的指纹，
     * 但所有条件都是混淆免疫的语义特征。
     */
    static List<MatchRule> buildDefaultRules() {
        List<MatchRule> rules = new ArrayList<>();

        // 联网验证层：业务 URL 子串 + HTTP 客户端类型是稳定语义锚点。
        // 方法形状只限定参数数量和返回类型，避免依赖每次混淆后变化的包名/类名。
        rules.add(new MatchRule.Builder("neutralize-online-validator")
                .stringContains("mybatispaidinfo/")
                .string("okhttp3/OkHttpClient")
                .methodShape(
                        MatchRule.methodShape("(Ljava/lang/String;)Z"),
                        MatchRule.methodShape("(Ljava/lang/Object;)V"))
                .action(MatchRule.Action.NEUTRALIZE_ALL)
                .build());

        // 激活/解绑对话框：只定位，不改动界面生命周期方法。
        rules.add(new MatchRule.Builder("neutralize-bind-unbind")
                .stringContains("unbind")
                .string("paidKey")
                .action(MatchRule.Action.LOG_ONLY)
                .build());

        // 联网响应闸：失败文案 + 单参数对象返回对象，定位连通性探测方法。
        rules.add(new MatchRule.Builder("neutralize-online-resp-gate")
                .string("response is not success")
                .methodShape(MatchRule.methodShape("(Ljava/lang/Object;)Ljava/lang/Object;"))
                .action(MatchRule.Action.RETURN_SUCCESS)
                .build());

        // 验证结果解析器：解析失败文案是唯一锚点，方法形状避免误改同类辅助方法。
        rules.add(new MatchRule.Builder("deserialize-validate-json")
                .stringContains("gson catch exception, the json string is")
                .action(MatchRule.Action.DESERIALIZE_JSON)
                .build());

        // 本地校验核心：解密后的分支常量组合定位校验类。
        rules.add(new MatchRule.Builder("neutralize-validator-core")
                .string("feimao")
                .string("keyNotExist")
                .action(MatchRule.Action.NEUTRALIZE)
                .build());

        // 非正版惩罚点：购买页 URL + 用户可见文案组合定位弹窗逻辑。
        rules.add(new MatchRule.Builder("neutralize-piracy-popup")
                .stringContains("brucege.com/pay/view")
                .stringContains("购买正版")
                .action(MatchRule.Action.NEUTRALIZE)
                .build());

        // 反破解探针：动态类名 + 探针异常文案是当前可用的稳定组合。
        rules.add(new MatchRule.Builder("neutralize-janetfilter-probe")
                .string("com/bruce/User")
                .string("field not exist")
                .action(MatchRule.Action.NEUTRALIZE)
                .build());

        // 状态闸门：Kotlin metadata 中的语义 getter 名。
        rules.add(new MatchRule.Builder("force-valid-true")
                .getter("getValid")
                .action(MatchRule.Action.FORCE_TRUE)
                .build());

        // 验证结果模型：Gson 字段契约组合，仅记录。
        rules.add(new MatchRule.Builder("detect-validate-result")
                .serial("validTo")
                .serial("paidKey")
                .action(MatchRule.Action.LOG_ONLY)
                .build());

        return rules;
    }

}

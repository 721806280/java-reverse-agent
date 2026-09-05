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

        // 规则1：联网验证层——所有联网请求端点都含 "mybatispaidinfo/" 子串
        // （reportAndCheckValid / check / unLock / do_not_crack_my_soft / getTrialKey），
        // 且真正发起请求的类常量池里都有 "okhttp3/OkHttpClient" 整串。二者并用作锚点，
        // 覆盖每日联网校验(k/a)、激活/解锁/解绑联网端点(ak/b/a)等全部联网场景。
        // 只改写已知的联网入口签名：k/a 的上报 boolean，以及 ak/b/a 的激活/解绑
        // void 入口。ak/b/a 同时承载本地文件读取和路径工具，不能再按类全量清空，
        // 否则全局配置页面会因路径/状态方法被破坏而消失。
        // 排在最前：k/a 同时含弹窗文案，须先由本规则认领，否则会被 piracy-popup 抢走。
        rules.add(new MatchRule.Builder("neutralize-online-validator")
                .stringContains("mybatispaidinfo/")
                .string("okhttp3/OkHttpClient")
                .methodDescriptor(
                        "(Ljava/lang/String;)Z",
                        "(Lcom/ccnode/codegenerator/ak/b/b;)V",
                        "(Lcom/ccnode/codegenerator/ak/c/d;)V")
                .action(MatchRule.Action.NEUTRALIZE_ALL)
                .build());

        // 规则2：激活/解绑对话框——"unbind" + "paidKey" 是绑定/解绑界面独有的文案/键名组合
        // （goToUnbindMsg / offlineunbind / go.to.unbind.page 等均含 unbind 子串）。
        // 这里只做 LOG_ONLY：该类本身包含 createCenterPanel/c()/doValidate() 等界面生命周期方法，
        // 全量清空会让“全局配置 -> 激活”对话框变成空白、入口无法打开。真正的联网调用已经
        // 在 ak/c、ak/b/a 规则中截断，因此保留该 UI 类的原始实现。
        rules.add(new MatchRule.Builder("neutralize-bind-unbind")
                .stringContains("unbind")
                .string("paidKey")
                .action(MatchRule.Action.LOG_ONLY)
                .build());

        // 规则3：联网响应拉闸类——"response is not success" 是 ak/c 独有的联网失败判断文案。
        // ak/c 在联网激活/校验失败时经 setValid(false) 拉闸。只替换其连通性探测方法，
        // 返回非空的 success DTO；本地文件校验、解绑和删除文件的方法全部保留，
        // 这样设置页仍可创建并显示状态。
        rules.add(new MatchRule.Builder("neutralize-online-resp-gate")
                .string("response is not success")
                .methodDescriptor(
                        "(Lcom/ccnode/codegenerator/ak/c/a;)Lcom/ccnode/codegenerator/ak/d/a;")
                .action(MatchRule.Action.RETURN_SUCCESS)
                .build());

        // 规则4：验证结果解析器——该类把服务端返回的内容转换为 ValidateNewResultData。
        // 某些部署/测试链路会直接传入 JSON，而不是 RSA 密文；保留 DTO 字段，让页面能显示
        // validTo/paidKey/userMac。锚点是解析失败文案，当前版本只出现在这个解析器中。
        rules.add(new MatchRule.Builder("deserialize-validate-json")
                .stringContains("gson catch exception, the json string is")
                .action(MatchRule.Action.DESERIALIZE_JSON)
                .build());

        // 规则5：本地校验核心——"feimao"/"keyNotExist" 是 .data 解密后的分支判断常量，
        // 只出现在真正跑校验逻辑的类里。空实现其 void 方法，让校验直接返回、不触发 setValid(false)。
        rules.add(new MatchRule.Builder("neutralize-validator-core")
                .string("feimao")
                .string("keyNotExist")
                .action(MatchRule.Action.NEUTRALIZE)
                .build());

        // 规则6："非正版"惩罚点——作者在功能类里嵌了本地校验+弹窗代码，
        // 文案都含 "购买正版" 和官网购买页 URL。用子串 "brucege.com/pay/view" + "购买正版" 做锚点，
        // 清空其 void 校验方法体，废掉弹窗惩罚网。（k/a 已由规则1认领，此处命中剩余 9 个内部类）
        rules.add(new MatchRule.Builder("neutralize-piracy-popup")
                .stringContains("brucege.com/pay/view")
                .stringContains("购买正版")
                .action(MatchRule.Action.NEUTRALIZE)
                .build());

        // 规则7：janetfilter 反破解探针——作者用 ASM 在运行时动态生成 com/bruce/User 类，
        // 其 show() 反射读 com.janetfilter.plugins.power.ArgsFilter 的 l2cached 字段来探测
        // 是否安装了 ja-netfilter 激活框架，装了就抛异常。这些类型引用是 ASM LdcInsn 的 Type
        // 常量（形如 Lcom/janetfilter/...;），常量池里唯一可见、又能通过噪声过滤的是 "com/bruce/User"
        // 动态类名 + "field not exist" 探针异常文案。命中后空实现其 void 方法，
        // 让探针直接返回、不再生成并执行探测字节码。
        rules.add(new MatchRule.Builder("neutralize-janetfilter-probe")
                .string("com/bruce/User")
                .string("field not exist")
                .action(MatchRule.Action.NEUTRALIZE)
                .build());

        // 规则8：状态闸门类——Kotlin @Metadata 含 getValid
        // 对应 Profile.getValid()；命中后让该 getter 恒返回 true
        rules.add(new MatchRule.Builder("force-valid-true")
                .getter("getValid")
                .action(MatchRule.Action.FORCE_TRUE)
                .build());

        // 规则9：验证结果模型——@SerializedName("validTo")
        // 仅记录，用于确认指纹定位准确（dry-run 时尤其有用）
        rules.add(new MatchRule.Builder("detect-validate-result")
                .serial("validTo")
                .serial("paidKey")
                .action(MatchRule.Action.LOG_ONLY)
                .build());

        return rules;
    }

}

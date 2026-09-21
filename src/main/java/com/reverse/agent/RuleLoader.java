package com.reverse.agent;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 从内置 classpath 资源加载指纹规则集。
 * <p>
 * 目标特定的锚点（序列化字段名 / 字符串子串 / getter 名）全部集中在 {@value #RESOURCE}，
 * 与本引擎代码解耦——换目标或目标改版时只改配置，无需重新编译。
 */
public final class RuleLoader {

    /**
     * 内置规则配置资源路径。
     */
    static final String RESOURCE = "/rules.json";

    private RuleLoader() {
    }

    /**
     * 加载默认规则集（内置 {@value #RESOURCE}）。
     *
     * @throws IllegalStateException 资源缺失或格式非法
     */
    public static List<MatchRule> loadDefault() {
        try (InputStream in = RuleLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("规则配置资源缺失: " + RESOURCE);
            }
            var reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            RuleSet ruleSet = new Gson().fromJson(reader, RuleSet.class);
            if (ruleSet == null || ruleSet.rules == null || ruleSet.rules.isEmpty()) {
                throw new IllegalStateException("规则配置为空: " + RESOURCE);
            }
            return ruleSet.rules.stream().map(RuleLoader::toRule).toList();
        } catch (UncheckedIOException | java.io.IOException e) {
            throw new IllegalStateException("读取规则配置失败: " + RESOURCE, e);
        }
    }

    private static MatchRule toRule(RuleDef def) {
        if (def.name == null || def.name.isBlank()) {
            throw new IllegalStateException("规则缺少 name 字段");
        }
        if (def.action == null) {
            throw new IllegalStateException("规则 " + def.name + " 缺少 action 字段");
        }
        MatchRule.Action action;
        try {
            action = MatchRule.Action.valueOf(def.action);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("规则 " + def.name + " 的 action 非法: " + def.action, e);
        }

        var builder = new MatchRule.Builder(def.name).action(action);
        if (def.serials != null) {
            builder.serial(def.serials.toArray(String[]::new));
        }
        if (def.stringContains != null) {
            builder.stringContains(def.stringContains.toArray(String[]::new));
        }
        if (def.getters != null) {
            builder.getter(def.getters.toArray(String[]::new));
        }
        return builder.minHits(def.minHits <= 0 ? 1 : def.minHits)
                .core(def.core)
                .build();
    }

    // 以下 DTO 的字段全部由 Gson 反射赋值，静态分析看不到写入点，故整体抑制相关告警。

    /**
     * {@code rules.json} 顶层结构。
     */
    @SuppressWarnings("unused")
    private static final class RuleSet {
        List<RuleDef> rules;
    }

    /**
     * 单条规则的 JSON 映射；字段缺省由 {@link #toRule} 兜底。
     */
    @SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
    private static final class RuleDef {
        String name;
        List<String> serials;
        @SerializedName("stringContains")
        List<String> stringContains;
        List<String> getters;
        String action;
        int minHits;
        boolean core;
    }
}

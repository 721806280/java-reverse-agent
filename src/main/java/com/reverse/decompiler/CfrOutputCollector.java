package com.reverse.decompiler;

import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * CFR 的输出收集器
 * <p>
 * 实现 {@link OutputSinkFactory}，拦截 CFR 反编译过程中的所有输出，将每个类的
 * 反编译结果捕获为 {@link DecompiledResult}，并汇总异常/摘要/进度信息。
 */
class CfrOutputCollector implements OutputSinkFactory {

    /**
     * 捕获的反编译结果（每个类一个）
     */
    private final List<DecompiledResult> decompiledResults = new ArrayList<>();

    /**
     * 汇总信息（SUMMARY 级别的输出）
     */
    private final List<String> summaries = new ArrayList<>();

    /**
     * 异常信息
     */
    private final List<String> exceptions = new ArrayList<>();

    /**
     * 进度信息
     */
    private final List<String> progress = new ArrayList<>();

    @Override
    public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
        // DECOMPILED 携带包名/类名/源码的完整数据，优先于纯 STRING
        if (available.contains(SinkClass.DECOMPILED)) {
            return Collections.singletonList(SinkClass.DECOMPILED);
        }
        return Collections.singletonList(SinkClass.STRING);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
        return switch (sinkType) {
            case JAVA -> {
                // 反编译正文：优先接收结构化数据，字符串则按原样兜底
                if (sinkClass == SinkClass.DECOMPILED) {
                    yield (Sink<T>) new Sink<SinkReturns.Decompiled>() {
                        @Override
                        public void write(SinkReturns.Decompiled sinkable) {
                            decompiledResults.add(new DecompiledResult(
                                    sinkable.getPackageName(),
                                    sinkable.getClassName(),
                                    sinkable.getJava()));
                        }
                    };
                }
                yield (Sink<T>) new Sink<String>() {
                    @Override
                    public void write(String sinkable) {
                        decompiledResults.add(new DecompiledResult(null, null, sinkable));
                    }
                };
            }
            case SUMMARY -> (Sink<T>) (Sink<String>) summaries::add;
            case EXCEPTION -> (Sink<T>) (Sink<String>) exceptions::add;
            case PROGRESS -> (Sink<T>) (Sink<String>) progress::add;
            default -> null;
        };
    }

    List<DecompiledResult> getDecompiledResults() {
        return decompiledResults;
    }

    List<String> getSummaries() {
        return summaries;
    }

    List<String> getExceptions() {
        return exceptions;
    }

    List<String> getProgress() {
        return progress;
    }

    /**
     * 单个类的反编译结果
     *
     * @param packageName 包名（可能为 null，此时 java 为原始文本）
     * @param className   类名（可能为 null）
     * @param java        反编译后的 Java 源码
     */
        record DecompiledResult(String packageName, String className, String java) {

        /**
             * 计算源码在输出目录中的相对路径
             * 例如：com.example.Foo -> com/example/Foo.java
             */
            String toRelativePath() {
                if (packageName == null || className == null) {
                    throw new IllegalStateException("无结构化反编译结果，无法计算相对路径");
                }
                String dir = packageName.isEmpty() ? "" : packageName.replace('.', '/') + '/';
                return dir + className + ".java";
            }
        }
}

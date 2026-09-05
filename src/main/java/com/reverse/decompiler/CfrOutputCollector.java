package com.reverse.decompiler;

import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * CFR 输出收集器：捕获反编译结果、异常和摘要。
 */
class CfrOutputCollector implements OutputSinkFactory {

    private final List<DecompiledResult> decompiledResults = new ArrayList<>();
    private final List<String> summaries = new ArrayList<>();
    private final List<String> exceptions = new ArrayList<>();
    private final List<String> progress = new ArrayList<>();

    @Override
    public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
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

    record DecompiledResult(String packageName, String className, String java) {

        /**
         * 计算源码在输出目录中的相对路径。
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

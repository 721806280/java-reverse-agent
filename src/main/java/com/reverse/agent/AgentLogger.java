package com.reverse.agent;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Agent 专用轻量日志器——不依赖任何日志框架，避免污染宿主 classpath。
 * 输出到 {@link System#out}，带时间戳。
 */
public final class AgentLogger {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String tag;
    private final PrintStream out;

    public AgentLogger(String tag) {
        this(tag, System.out);
    }

    public AgentLogger(String tag, PrintStream out) {
        this.tag = tag;
        this.out = out;
    }

    public void info(String msg) {
        out.println("[" + LocalTime.now().format(TIMESTAMP) + "] [" + tag + "] " + msg);
    }

    public void err(String msg) {
        out.println("[" + LocalTime.now().format(TIMESTAMP) + "] [" + tag + "] ❌ " + msg);
    }
}

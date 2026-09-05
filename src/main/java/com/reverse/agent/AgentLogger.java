package com.reverse.agent;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Agent 专用轻量日志器——不依赖任何日志框架，避免污染宿主 classpath。
 * 输出到 {@link System#out}，带时间戳。
 */
public final class AgentLogger {

    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    private final String tag;
    private final PrintStream out;

    public AgentLogger(String tag) {
        this(tag, System.out);
    }

    public AgentLogger(String tag, PrintStream out) {
        this.tag = tag;
        this.out = out;
    }

    private static String now() {
        return TS.format(new Date());
    }

    public void info(String msg) {
        out.println("[" + now() + "] [" + tag + "] " + msg);
    }

    public void err(String msg) {
        out.println("[" + now() + "] [" + tag + "] ❌ " + msg);
    }
}

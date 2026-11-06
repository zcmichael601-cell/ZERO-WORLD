package com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics;

public final class ConsoleLoggingBootstrap {

    private ConsoleLoggingBootstrap() {
    }

    public static void initialize() {
        System.getProperties().putIfAbsent("console_log_level", "ALL");
        System.getProperties().putIfAbsent("donglog_console_enabled", "true");
        System.getProperties().putIfAbsent("odin.diagnostics.output", "console");
        diagnosticConsoleEnabled();
    }

    public static boolean diagnosticConsoleEnabled() {
        String output = System.getProperty("odin.diagnostics.output", "logger");
        if (!"console".equalsIgnoreCase(output) && !"logger".equalsIgnoreCase(output)) {
            throw new IllegalArgumentException("odin.diagnostics.output 仅支持 console 或 logger");
        }
        return "console".equalsIgnoreCase(output);
    }
}

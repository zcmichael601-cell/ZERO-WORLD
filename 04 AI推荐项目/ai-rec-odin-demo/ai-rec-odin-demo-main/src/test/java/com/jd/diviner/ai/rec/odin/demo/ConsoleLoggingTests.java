package com.jd.diviner.ai.rec.odin.demo;

import com.jd.diviner.ai.rec.odin.demo.app.support.diagnostics.ConsoleLoggingBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleLoggingTests {

    @Test
    void enablesPlatformConsoleBeforeLoggingInitializationWithoutEnablingDebug() {
        String level = System.getProperty("console_log_level");
        String enabled = System.getProperty("donglog_console_enabled");
        String rootLevel = System.getProperty("donglog_root_level");
        String output = System.getProperty("odin.diagnostics.output");
        try {
            System.clearProperty("console_log_level");
            System.clearProperty("donglog_console_enabled");
            System.clearProperty("odin.diagnostics.output");
            ConsoleLoggingBootstrap.initialize();
            assertEquals("ALL", System.getProperty("console_log_level"));
            assertEquals("true", System.getProperty("donglog_console_enabled"));
            assertEquals("console", System.getProperty("odin.diagnostics.output"));
            assertTrue(ConsoleLoggingBootstrap.diagnosticConsoleEnabled());
            assertEquals(rootLevel, System.getProperty("donglog_root_level"));
            ConsoleLoggingBootstrap.initialize();
            assertEquals("ALL", System.getProperty("console_log_level"));
            System.setProperty("console_log_level", "WARN");
            System.setProperty("donglog_console_enabled", "false");
            System.setProperty("odin.diagnostics.output", "logger");
            ConsoleLoggingBootstrap.initialize();
            assertEquals("WARN", System.getProperty("console_log_level"));
            assertEquals("false", System.getProperty("donglog_console_enabled"));
            assertFalse(ConsoleLoggingBootstrap.diagnosticConsoleEnabled());
        } finally {
            restore("console_log_level", level);
            restore("donglog_console_enabled", enabled);
            restore("odin.diagnostics.output", output);
        }
    }

    @Test
    void invalidDiagnosticOutputDoesNotSilentlyDisableLogs() {
        String output = System.getProperty("odin.diagnostics.output");
        try {
            System.setProperty("odin.diagnostics.output", "unknown");
            assertThrows(IllegalArgumentException.class, ConsoleLoggingBootstrap::diagnosticConsoleEnabled);
        } finally {
            restore("odin.diagnostics.output", output);
        }
    }

    private void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}

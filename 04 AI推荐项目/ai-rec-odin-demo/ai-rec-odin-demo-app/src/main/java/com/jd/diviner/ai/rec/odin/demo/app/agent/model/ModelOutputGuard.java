package com.jd.diviner.ai.rec.odin.demo.app.agent.model;

public final class ModelOutputGuard {
    private final Channel content = new Channel("CONTENT");
    private final Channel reasoning = new Channel("REASONING");
    private int toolArgumentChars;

    public void content(String text) { content.accept(text); }
    public void reasoning(String text) { reasoning.accept(text); }
    public void toolArguments(String text) {
        toolArgumentChars += text == null ? 0 : text.length();
        if (toolArgumentChars > 65536) throw new ModelGenerationException("TOOL_ARGUMENT_LIMIT");
    }

    private static final class Channel {
        private final String name;
        private final StringBuilder tail = new StringBuilder();
        private int chars;

        private Channel(String name) { this.name = name; }

        private void accept(String text) {
            if (text == null) return;
            for (int index = 0; index < text.length(); index++) {
                tail.append(text.charAt(index));
                if (++chars > 48000) throw new ModelGenerationException(name + "_LIMIT");
                if (chars % 128 != 0) continue;
                if (tail.length() > 4096) tail.delete(0, tail.length() - 4096);
                String window = tail.toString();
                for (int period = 32; period <= 512; period++) {
                    int repeated = period * Math.max(6, (768 + period - 1) / period);
                    if (window.length() < repeated) continue;
                    int start = window.length() - repeated;
                    if (window.regionMatches(start, window, start + period, repeated - period)) {
                        throw new ModelGenerationException("REPETITIVE_" + name);
                    }
                }
            }
        }
    }
}

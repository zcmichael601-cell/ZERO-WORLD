package com.jd.diviner.ai.rec.odin.demo.app.infrastructure.travel;

import java.net.URI;

@FunctionalInterface
public interface TravelTransport {
    String get(URI uri);
}

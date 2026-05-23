package io.jcgraph.content;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand, cached decompiler for the bytecode content layer.
 *
 * <p>Uses CFR (Java 8 compatible) and captures the decompiled source straight
 * into a String via a custom {@link OutputSinkFactory} — no disk round-trip.
 * On JDK 11+ this class is the single seam to swap for Vineflower + the
 * {@code bsm} bytecode-source line mapping.</p>
 */
public class Decompiler {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Decompile the class at {@code classFilePath} to its full Java source (cached). */
    public String decompile(String classFilePath) {
        String cached = cache.get(classFilePath);
        if (cached != null) {
            return cached;
        }
        String src = run(classFilePath);
        cache.put(classFilePath, src);
        return src;
    }

    private String run(String classFilePath) {
        final StringBuilder sb = new StringBuilder();
        OutputSinkFactory sink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                if (sinkType == SinkType.JAVA && available.contains(SinkClass.STRING)) {
                    return Collections.singletonList(SinkClass.STRING);
                }
                return Collections.emptyList();
            }

            @Override
            public <T> Sink<T> getSink(final SinkType sinkType, final SinkClass sinkClass) {
                return sinkable -> {
                    if (sinkType == SinkType.JAVA) {
                        sb.append(sinkable);
                    }
                };
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");

        try {
            CfrDriver driver = new CfrDriver.Builder()
                    .withOutputSink(sink)
                    .withOptions(options)
                    .build();
            driver.analyse(Collections.singletonList(classFilePath));
        } catch (Throwable t) {
            return "// decompile failed for " + classFilePath + ": " + t;
        }
        return sb.length() == 0 ? "// decompiler produced no output for " + classFilePath : sb.toString();
    }
}

package io.ltr8.tson.parser.ast;

import java.util.List;

/**
 * {@code map = "{" ws map-entry *( separator map-entry ) ws "}"} (§2.6, §7.4).
 *
 * <p>Entry order is preserved and duplicate keys are <em>not</em> deduplicated or even detected
 * here: "last value wins" and duplicate-key warnings are resolver-layer concerns (§2.6), and
 * textual key-identity comparison requires NFC normalization the structural parser doesn't do.
 */
public record MapValue(List<MapEntry> entries) implements CoreValue {

    public MapValue {
        entries = List.copyOf(entries);
    }

    /** {@code map-entry = data-value ws "=>" ws scoped-value}. The key is a full data-value, not just a token (§2.6). */
    public record MapEntry(DataValue key, ScopedValue value) {
    }
}

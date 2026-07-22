package io.ltr8.tson.parser.ast;

import java.util.List;

/**
 * {@code record = "{" ws field *( separator field ) ws "}"} (§2.5, §7.4).
 *
 * <p>Field order is preserved and duplicates are <em>not</em> deduplicated here: "last value
 * wins" for duplicate field names is a resolver-layer rule (§2.5), and field-name identity
 * (NFC-normalized comparison) is likewise resolver-layer (§7.2.1). This is the structural
 * parser only — it faithfully preserves every field as written.
 */
public record RecordValue(List<Field> fields) implements CoreValue {

    public RecordValue {
        fields = List.copyOf(fields);
    }

    /** {@code field = field-name ws ":" ws scoped-value}. {@code name} is the field-name token's decoded text. */
    public record Field(String name, ScopedValue value) {
    }
}

package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Map;

/**
 * {@code type}'s one field, {@code chain}, for a hand-built constructor record, with every kernel entry its type
 * reaches copied into the schema being built. A constructor composing {@code atom} flattens it in first
 * ([TSON-SCHEMA] §4.1), and its body class carries a component for it, so a schema standing in for the kernel's
 * own states it as the kernel does.
 */
final class KernelChainFields {

    private KernelChainFields() {
    }

    /** The kernel's own {@code chain} field, its type's closure added to {@code entries}. */
    static Map.Entry<String, RecordField> chainField(Map<String, Top> entries) {
        TsonSchema kernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
        RecordField declared = ((RecordBody) kernel.entries().get("type")).fields().get("chain");
        copy(declared.type(), kernel, entries);
        return Map.entry("chain", declared);
    }

    private static void copy(TypeRef ref, TsonSchema kernel, Map<String, Top> entries) {
        String name = ref.name();
        Top value = kernel.entries().get(name);
        if (value == null || entries.containsKey(name)) {
            return;
        }
        entries.put(name, value);
        switch (value) {
            case RecordBody record -> record.fields().values().forEach(f -> copy(f.type(), kernel, entries));
            case ArrayBody array -> copy(array.elementType(), kernel, entries);
            case MapBody map -> {
                copy(map.keyType(), kernel, entries);
                copy(map.valueType(), kernel, entries);
            }
            case TupleBody tuple -> tuple.elements().stream().map(TupleElement::elementType)
                    .forEach(e -> copy(e, kernel, entries));
            case ChoiceBody choice -> choice.variants().forEach(v -> copy(v, kernel, entries));
            case Alias alias -> copy(alias.target(), kernel, entries);
            default -> {
            }
        }
    }
}

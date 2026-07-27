package io.ltr8.tson.parser.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds object-binding mode's own Java classes to a schema, eagerly -- the "bind" step {@link
 * TsonObjectBinding#factoryRegistry} runs before compiling, so a schema that can't be bound to real
 * {@code schema.meta} objects fails clearly, with every problem entry named at once, rather than
 * lazily, one entry at a time, only once some unrelated read happens to reach it. This class is the
 * verb; {@link ObjectRecordShapeFactory} is the noun that consumes {@link #bind}'s own result --
 * once {@link #bind} returns, that factory holds nothing but the finished, immutable {@code
 * Map<String, DataClassRecord>}, the same verb/noun split this project's own pipeline vocabulary
 * already uses elsewhere (e.g. {@code TsonSchemaCompiler}/{@code TsonCompiledSchema}).
 *
 * <p>Walks every {@code record}-shaped entry in the schema (i.e. every entry whose {@link
 * TypeDefinition#body()} is a {@link RecordBody} -- the ones that would actually reach {@link
 * ObjectRecordShapeFactory#shapeFor} once compiled), resolving {@code binder}'s own schema-name ->
 * Java-{@link Class} mapping and then {@code context}'s own {@link DataClassRecord} descriptor for
 * each.
 *
 * <p><b>An entry that resolves to a real, existing Java class which isn't a record is silently
 * skipped, not treated as a failure.</b> A handful of real meta-kernel entries (its own {@code
 * atom}/{@code product}/{@code sum}/{@code top} base-kind declarations, and {@code type_argument})
 * mangle to a genuine {@code schema.meta} class that's deliberately a sealed marker interface, not
 * a plain record (see {@link SchemaMetaTypeNameBinder}'s own Javadoc) -- these are meta-schema
 * machinery real application data is never actually read as an instance of, so failing the whole
 * schema's binding over them would be a false positive -- confirmed empirically, not assumed, by
 * running this against the real, fully registered meta-kernel.tn1 fixture (0 genuine problems, 5
 * legitimately skipped, 23 bound).
 */
public final class TsonObjectBinder {

    private TsonObjectBinder() {
    }

    /**
     * @throws IllegalStateException naming every entry that failed to resolve, if any did
     */
    public static Map<String, DataClassRecord> bind(TsonSchema schema, DataBindContext context,
                                                      TsonTypeNameBinder binder) {
        Map<String, DataClassRecord> bound = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, TypeDefinition> entry : schema.entries().entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue().body() instanceof RecordBody)) {
                continue;
            }
            Class<?> target;
            try {
                target = binder.resolve(name);
            } catch (ClassNotFoundException e) {
                problems.add("'" + name + "': " + e.getMessage());
                continue;
            }
            DataClass descriptor;
            try {
                descriptor = context.getDescriptor(target);
            } catch (DataBindException e) {
                problems.add("'" + name + "' resolved to " + target + ", but tson-bind could not build a "
                        + "descriptor for it: " + e.getMessage());
                continue;
            }
            if (!(descriptor instanceof DataClassRecord recordDescriptor)) {
                // Not a failure -- a real class that isn't a record (e.g. Top/Atom/Product/Sum,
                // deliberately sealed marker interfaces) is meta-schema machinery this factory was
                // never going to construct anyway; see this class's own Javadoc.
                continue;
            }
            bound.put(name, recordDescriptor);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("object-binding mode could not resolve a Java class for "
                    + problems.size() + (problems.size() == 1 ? " schema entry" : " schema entries") + ":\n  "
                    + String.join("\n  ", problems));
        }
        return Map.copyOf(bound);
    }
}

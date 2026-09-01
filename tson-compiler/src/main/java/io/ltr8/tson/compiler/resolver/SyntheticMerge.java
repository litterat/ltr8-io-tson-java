package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [TSON-SCHEMA] §8.2's merge pass: <b>one closed synthetic entry per distinct concrete form, schema-wide</b>,
 * whichever of the two lift channels produced each candidate.
 *
 * <p>Both channels name a form by the same function of the same thing -- the binding record with every inner
 * form already reduced to its entry name. {@code TemplateMaterialiser} always satisfies that, closing
 * applications before it names. {@code SchemaDesugarer} satisfies it for a nested <em>sugar</em> form,
 * lifting innermost-first so the inner entry exists by the time the outer is named, and cannot satisfy it for
 * a nested <em>application</em>: {@code box<text>} has no entry until materialisation. So a form lifted
 * eagerly with an application in a slot is named from an unreduced record, and {@code [box<text>]} written
 * directly lands apart from {@code [box<T>]} closed with {@code T := text}. §8.2 states that split as the
 * reason the pass is mandatory:
 *
 * <blockquote>The merge pass is required, not an optimisation: a closed lift hashes its binding record at
 * desugar, before its inner applications are rewritten to entry names, while an open lift hashes the closed
 * record at materialisation, so without a pass that re-derives every synthetic's identity from its resolved
 * record, {@code [box<text>]} written directly and {@code [box<T>]} closed with {@code T := text} land on two
 * entries for one type.</blockquote>
 *
 * <p><b>The closed-record name wins, never the eager one.</b> It is a function of the resolved form alone,
 * so two schemas reaching one form by different spellings agree on it -- §8.2's determinism SHOULD, and what
 * lets the import merge unify rather than collide. Picking the eager name instead, or the smaller of the two,
 * would make an entry's name depend on which spellings happened to appear beside it.
 *
 * <p><b>Only a form whose binding held an application moves.</b> Every other synthetic was already named from
 * a reduced record and re-derives to the name it has, so the pass is a no-op over them -- which is what keeps
 * it from renaming the whole namespace on the strength of a rule it is only enforcing at one edge.
 */
final class SyntheticMerge {

    private SyntheticMerge() {
    }

    /**
     * The renames the pass calls for: each eagerly lifted form that re-derives to a different name, mapped to
     * that name. Empty -- the ordinary case -- when no lifted form held an application.
     *
     * <p>{@code declarations} is the <em>desugared</em> document's, so a lifted name's declaration is the
     * {@code !C value} construction the phase injected and its binding record is recoverable from it. Open
     * synthetics are skipped: §8.2 gives them their own identity rule (structural equality of the held body
     * up to consistent renaming of parameters), and their bodies are held rather than closed.
     */
    static Map<String, String> renames(Map<String, SchemaMap.Declaration> declarations, Set<String> generated,
                                        TemplateMaterialiser materialiser) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (String name : generated) {
            SchemaMap.Declaration declaration = declarations.get(name);
            if (!(declaration != null && declaration.typeDef() instanceof Instance instance)
                    || !instance.typeParams().isEmpty()) {
                continue;
            }
            String head = instance.value().typeRef().orElse(null);
            if (head == null || !(instance.value().coreValue() instanceof RecordValue binding)
                    || !holdsApplication(binding)) {
                continue;
            }
            String closed = closedName(materialiser, head, binding.fields());
            if (closed != null && !closed.equals(name)) {
                renames.put(name, closed);
            }
        }
        return renames;
    }

    /**
     * Every reference in {@code entries} that names a merged form, rewritten in place to the name it merged
     * onto. Keys are left alone: which map an entry belongs in, and whether a renamed one moves or is dropped
     * because the other channel already published it, is {@code SchemaResolver}'s to decide -- it is the one
     * holding both the local and the materialised map.
     */
    static void rewrite(Map<String, TypeDefinition> entries, Map<String, String> renames) {
        for (Map.Entry<String, TypeDefinition> entry : entries.entrySet()) {
            entry.setValue(TemplateMaterialiser.mapRefs(entry.getValue(), ref -> rename(ref, renames)));
        }
    }

    /** One type-ref rewritten, its own arguments included -- an application may name a merged form. */
    private static TypeRef rename(TypeRef ref, Map<String, String> renames) {
        String to = renames.get(ref.name());
        return to == null ? ref : new TypeRef(to, ref.arguments(), ref.annotations());
    }

    /**
     * The name the form takes with its applications closed, or {@code null} where closing cannot answer.
     * Materialisation has already closed every application in this entry's body, so this reads its memo; a
     * schema whose materialisation failed is one that is being reported anyway, and a rename derived from a
     * half-closed record would be a second, invented problem on top of the real one.
     */
    private static String closedName(TemplateMaterialiser materialiser, String head,
                                      List<RecordValue.Field> fields) {
        try {
            return materialiser.closedFormName(head, fields);
        } catch (TsonSchemaValidationException | UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Whether any slot of the binding record holds an application -- {@code type_ref}'s record form, the one
     * shape {@code SchemaDesugarer} writes for an application standing in a slot. Nested records and arrays
     * recurse, so {@code [[box<text>]]} and {@code (box<text> | int32)} are found alike.
     */
    private static boolean holdsApplication(CoreValue value) {
        return switch (value) {
            case RecordValue record -> WireForm.isApplication(record)
                    || record.fields().stream().anyMatch(f -> holdsApplication(f.value().value().coreValue()));
            case ArrayValue array ->
                    array.elements().stream().anyMatch(e -> holdsApplication(e.value().coreValue()));
            default -> false;
        };
    }
}

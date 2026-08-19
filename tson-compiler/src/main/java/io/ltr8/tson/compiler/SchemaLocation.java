package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * Where the rule a reader is enforcing was declared -- the schema half of a {@link Diagnostic}'s location,
 * carried through a read as one value.
 *
 * <p><b>One carrier rather than separate withers, because the components are one fact.</b> A reader stamping
 * its declaration's position without its name produces a position a consumer cannot attribute -- {@code
 * 110:3:4858} is core.tn's line for {@code int32} and nothing says so -- and stamping the two through
 * independent calls makes it possible for a reader to claim one and inherit the other from whichever reader
 * ran before it. {@link TsonReadContext#withSchemaLocation} takes all of it at once so that cannot happen.
 *
 * <p>{@code position} is {@link Optional} and the other two are not, which is the honest asymmetry: a
 * desugar-injected entry ({@code array_text_d5ed9ca5}) has a name in a schema but no line in any source text,
 * so it can be pointed at even though it cannot be opened.
 *
 * <p><b>{@code schemaId} is the schema that <em>declared</em> the entry, not the one being read against.</b>
 * They differ whenever an {@code !!import} is involved -- a four-line schema importing core.tn enforces
 * {@code /int32} at core.tn's line 110 -- which is why it comes from {@link TsonLinkedSchema#originOf}, the
 * record linking keeps of where each merged entry came from, rather than from the compiled schema's own id.
 *
 * <p><b>{@code declaration} is the entry a reader was built for, not necessarily the name that reader's own
 * message uses.</b> They coincide everywhere but one path: {@code RecordBindReader.rebindContainerIfNeeded}
 * rebuilds a container reader under the consuming <em>field's</em> name, which is what reads naturally in a
 * message, and carries the original declaration's location through unchanged so the pointer still lands on
 * the entry that declares the rule.
 */
public record SchemaLocation(String schemaId, String declaration, Optional<SourcePosition> position) {

    /**
     * The RFC 6901 pointer into the schema's own {@code map<type_name, type_definition>} -- {@code /my_type},
     * the {@code keywordLocation} half of {@link Diagnostic}'s schema end. Never {@code ""}: a reader always
     * enforces some declaration, where the schema-side report sites also emit the root pointer for a problem
     * with a document as a whole.
     */
    public String pointer() {
        return "/" + declaration;
    }
}

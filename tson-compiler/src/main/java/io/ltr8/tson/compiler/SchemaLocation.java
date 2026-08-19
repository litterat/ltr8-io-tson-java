package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

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
 * <p>{@code position} is {@link Optional} and {@code declaration} is not, which is the honest asymmetry: a
 * desugar-injected entry ({@code array_text_d5ed9ca5}) has a name in the resolved schema but no line in any
 * source text, so it can be pointed at even though it cannot be opened.
 *
 * <p><b>{@code declaration} is the entry a reader was built for, not necessarily the name that reader's own
 * message uses.</b> They coincide everywhere but one path: {@code RecordBindReader.rebindContainerIfNeeded}
 * rebuilds a container reader under the consuming <em>field's</em> name, which is what reads naturally in a
 * message, and carries the original declaration's location through unchanged so the pointer still lands on
 * the entry that declares the rule.
 */
public record SchemaLocation(String declaration, Optional<SourcePosition> position) {

    /**
     * The location of the entry a {@code ValueReaderFactory} is building a reader for -- {@code declaration}
     * is the entry's own declared name, which every factory is handed, and the position is the declaration's
     * own. Every reader stamps one of these; nothing else constructs one.
     */
    public static SchemaLocation of(String declaration, TypeDefinition definition) {
        return new SchemaLocation(declaration, definition.position());
    }

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

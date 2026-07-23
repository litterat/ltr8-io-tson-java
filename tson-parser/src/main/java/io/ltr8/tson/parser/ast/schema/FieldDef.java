package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.Annotation;
import io.ltr8.tson.parser.ast.TokenValue;

import java.util.List;
import java.util.Optional;

/**
 * {@code field-def = *annotation field-name ws ":" ws ( field-type field-modifier / field-type /
 * field-modifier )} (Part 2 §12.1, §5.2) -- one record field. Exactly one of {@code type}/{@code
 * modifier} may be absent, never both: a bare {@code field:} with neither a type-ref nor a
 * modifier is not a grammar production. Where {@code type} is absent, the type is elided and
 * inherited from a refinement/composition source (§5.7's "elided type-refs") -- legal only there;
 * the parser is responsible for rejecting an elided type-ref in a fresh record definition (§5.2).
 */
public record FieldDef(List<Annotation> annotations, String name, Optional<FieldType> type,
                        Optional<Modifier> modifier) implements RecordEntry {

    public FieldDef {
        annotations = List.copyOf(annotations);
        if (type.isEmpty() && modifier.isEmpty()) {
            throw new IllegalArgumentException("a field-def needs a type-ref, a modifier, or both");
        }
    }

    /** {@code field-type = type-ref ["?"]} -- {@code optional} is FIELD optionality (§5.2), not element/tuple optionality. */
    public record FieldType(TypeRef typeRef, boolean optional) {
    }

    /**
     * {@code field-modifier = ws ("~" / "=") ws (token / absent)} -- {@code ~} is {@link
     * Kind#DEFAULT}, {@code =} is {@link Kind#FIXED} (§5.2). The value is a bare token or the
     * absent sentinel only -- never annotated, never typed, never a container (see {@code
     * SPEC-FEEDBACK.md} #15 on why this is narrower than a full {@code data-value} despite §12.1's
     * own introductory prose suggesting otherwise).
     */
    public record Modifier(Kind kind, Value value) {

        public enum Kind {
            DEFAULT, FIXED
        }

        public sealed interface Value {

            record Literal(TokenValue token) implements Value {
            }

            /** {@code = _} -- valid only on an OPTIONAL field (§5.2); {@code ~ _} is always a resolver error. */
            record Absent() implements Value {
            }
        }
    }
}

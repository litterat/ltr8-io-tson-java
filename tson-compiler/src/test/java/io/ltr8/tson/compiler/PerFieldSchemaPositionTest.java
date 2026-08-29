package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeRef;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A read diagnostic's two schema-end components descend together: the pointer names the field
 * ({@code /r/age}) and the position is the <b>field's own</b> line, not the enclosing declaration's.
 *
 * <p>Before this, positions were per declaration -- from the declaration's own name token, with
 * {@code RecordField} carrying none -- so six broken fields of one record all reported the record's
 * own line. An author fixing them was pointed at one line six times and an editor had one place
 * to jump to, while the pointer beside it had already said which field.
 *
 * <p>{@code RecordField.position} is {@code @Unbound}, on {@code TypeDefinition.position}'s precedent:
 * §8.1's {@code record_field} declares no such field, so nothing fills it and strict binding would call it a
 * mismatch. It is not carried in the annotation channel, which is schema data -- it resolves one hop against
 * the governing meta (§6) and round-trips into resolver output, none of which is true of a source position.
 */
class PerFieldSchemaPositionTest {

    private static final String ID = "https://example.test/pos.tn";

    private static List<Diagnostic> read(String declarations, String data) {
        String schema = """
                !!id:"https://example.test/pos.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.tree(core).get(ID);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        compiled.get("r").read(TestDocuments.document(data, problems));
        return problems.diagnostics();
    }

    private static int lineOf(Diagnostic diagnostic) {
        return diagnostic.schemaPosition().orElseThrow().line();
    }

    /**
     * <b>The headline: one line each, and the column too.</b> Six problems in one record used to carry one
     * position between them. Column matters as much as line for an editor placing a caret, and a field's
     * name token has one where its declaration does not.
     */
    @Test
    void eachFieldCarriesItsOwnLineAndColumn() {
        // The three header directives and the opening brace put `small` on line 5, `r` on 6, its fields on 7..9.
        List<Diagnostic> found = read("""
                  small => !integer ^ { max: 100 }
                  r => {
                    a: small
                    b: small
                    c: small
                  }""", "!r { a: 500  b: 500  c: 500 }");

        assertEquals(List.of(7, 8, 9), found.stream().map(PerFieldSchemaPositionTest::lineOf).toList(),
                found::toString);
        // The text block leaves declarations at column 1 and their fields at column 3, so a column that is
        // not the declaration's is itself part of the evidence.
        assertEquals(List.of(3, 3, 3),
                found.stream().map(d -> d.schemaPosition().orElseThrow().column()).toList(),
                "the field's own name token, not the declaration's");
    }

    /**
     * <b>A field whose type is a sugar form is rebuilt by desugaring</b>, and a rebuilt node is a different
     * identity in a table keyed by one -- the trap {@code SchemaDesugarer.schemaMap} already documents for
     * declarations, one level down. Any record with a single {@code [T]} field hits it, so this is the common
     * case rather than an edge.
     */
    @Test
    void aFieldRewrittenByDesugaringKeepsItsPosition() {
        List<Diagnostic> found = read("""
                  r => {
                    tags: [text; 1..2]
                  }""", "!r { tags: [x y z] }");

        assertEquals(6, lineOf(found.getFirst()), found::toString);
    }

    /**
     * <b>And one rewritten by §8.3's use-site flattening.</b> Every field's type-ref is rewritten there --
     * that is what puts {@code @alias} on it -- so the walk rebuilds every {@code RecordField} in the schema.
     * A rebuild naming components positionally drops the ones it does not mention, and no test comparing
     * resolved values can catch that, since position is excluded from equality. {@code RecordField.withType}
     * exists so the rebuild cannot forget.
     */
    @Test
    void aFieldRewrittenByReferenceFlatteningKeepsItsPosition() {
        List<Diagnostic> found = read("""
                  small => !integer ^ { max: 100 }
                  pct   => small
                  r => {
                    v: pct
                  }""", "!r { v: 500 }");

        assertEquals(8, lineOf(found.getFirst()), found::toString);
        assertTrue(found.getFirst().message().startsWith("'pct':"), "and still names the use site");
    }

    /** A field the document never mentions is located at its own declaration too, not at the record's. */
    @Test
    void aMissingRequiredFieldIsLocatedAtItsOwnDeclaration() {
        List<Diagnostic> found = read("""
                  r => {
                    present: text
                    absent: text
                  }""", "!r { present: \"x\" }");

        assertEquals(Diagnostic.Code.FIELD_REQUIRED, found.getFirst().code());
        assertEquals(7, lineOf(found.getFirst()), found::toString);
    }

    /**
     * <b>Position stays out of identity</b>, on {@code annotations}' footing and for {@code TypeDefinition}'s
     * stated reason: the resolver test suite compares hand-built expected values against really-resolved
     * ones, and a position in equality would stop two representations of one logical field comparing equal.
     */
    @Test
    void positionDoesNotParticipateInIdentity() {
        RecordField bare = new RecordField("f", TypeRef.of("text"), FieldState.REQUIRED, Optional.empty());
        RecordField located = bare.withPosition(Optional.of(new SourcePosition() {
            @Override
            public int line() {
                return 3;
            }

            @Override
            public int column() {
                return 5;
            }

            @Override
            public int byteOffset() {
                return 40;
            }
        }));

        assertEquals(bare, located);
        assertEquals(bare.hashCode(), located.hashCode());
        assertNotEquals(Optional.empty(), located.position(), "kept, just not compared");
    }
}

package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema type nothing in the bind context resolves to a Java class is a <b>wiring</b> problem, and the
 * schema pipeline says so wherever it meets one.
 *
 * <p><b>Three answers to one condition is what this pins against.</b> {@code TsonMissingBindingException}
 * exists to stop a missing binding reading as <i>this library cannot do that</i> -- its Javadoc records a
 * downstream service turning that shape into a 501 -- and a bind read honours it by throwing the exception
 * unwrapped. The schema pipeline used to undo that twice over: {@code bindAnnotationValue}'s catch-all
 * relabelled it {@code UnsupportedOperationException}, so it reported {@code NOT_IMPLEMENTED}; and the loop
 * for annotations written before the declared name reported {@code ofSchemaError} whatever the type, so the
 * same annotation moved across the {@code =>} reported {@code SCHEMA_ERROR} and exited 1 instead of 70.
 *
 * <p>{@code BIND_MISMATCH} is the answer to both, and to the constructor case beside them: neither <i>your
 * schema is wrong</i> nor <i>this could not be checked</i>, but <i>this application is wired wrong</i>, with
 * the message naming one of the caller's own classes.
 *
 * <p><b>Position-independence is the property, not the code.</b> A correct classification does not depend on
 * where in a declaration the construct was written, which is what {@link #anAuthorErrorIsTheSameWhereverItIsWritten}
 * asserts alongside: the contrast is what makes the annotation pair evidence of a bug rather than of a
 * convention.
 */
class BindMismatchClassificationTest {

    private static final String META_X = "https://example.test/meta-bindmismatch.tn";

    /** A meta layer declaring a constructor whose instances are DATA-kinded -- and no Java class for it. */
    private static final String META_SCHEMA = """
            !!id:"https://example.test/meta-bindmismatch.tn"
            !!meta:"https://tson.io/2026/34/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/34/m/meta.tn"
            {
              notification => ~data & { path: text }
            }
            """;

    private static final String GOVERNED_HEADER = """
            !!id:"https://example.test/api-bindmismatch.tn"
            !!meta:"https://example.test/meta-bindmismatch.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            """;

    private static final String ORDINARY_HEADER = """
            !!id:"https://example.test/bindmismatch.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            """;

    private static Tson tson() {
        return Tson.builder()
                .schemaSource(TsonSchemaSource.ofMap(Map.of(META_X, META_SCHEMA)))
                .dataBindContext(SchemaMetaNameBinder.defaultContext())
                .build();
    }

    private static Diagnostic only(String document) {
        List<Diagnostic> problems = tson().validateSchema(document);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    /**
     * {@code @data} names the kernel's own {@code data} base kind, which
     * {@code TsonMissingBindingException}'s Javadoc gives as an example of a type a consumer legitimately
     * never binds. §6 puts an annotation written before the name on the <em>name</em> and one after the
     * arrow on the <em>definition</em>, which used to be two different code paths with two different codes.
     */
    @Test
    void anUnboundAnnotationTypeIsAWiringProblemInEitherPosition() {
        Diagnostic beforeTheName = only(ORDINARY_HEADER + "{\n@data:_ foo => text\n}\n");
        Diagnostic afterTheArrow = only(ORDINARY_HEADER + "{\nfoo => @data:_ text\n}\n");

        assertEquals(Diagnostic.Code.BIND_MISMATCH, beforeTheName.code());
        assertEquals(Diagnostic.Code.BIND_MISMATCH, afterTheArrow.code());
        assertEquals(beforeTheName.code(), afterTheArrow.code(),
                "the same annotation, moved across the arrow, is the same problem");
        for (Diagnostic d : List.of(beforeTheName, afterTheArrow)) {
            assertEquals("/foo", d.schemaPointer().orElseThrow(), "located at the declaration");
            assertTrue(d.message().contains("no bound Java class for 'data'"), d::message);
        }
    }

    /**
     * The constructor case, which reaches the same condition through {@code bindAtomInstance} rather than
     * through an annotation: a meta layer's own constructor the consumer never registered a class for.
     *
     * <p>It is reported <b>per declaration, with a position</b>. Escaping resolution instead left {@code
     * Tson.validateSchema}'s own outer catch to report it against the document as a whole -- the right code,
     * with no declaration name and no line, for a problem that has both.
     */
    @Test
    void anUnboundMetaLayerConstructorIsReportedAtTheDeclarationThatAppliedIt() {
        Diagnostic d = only(GOVERNED_HEADER + "{\nping => !notification { path: \"/ping\" }\n}\n");

        assertEquals(Diagnostic.Code.BIND_MISMATCH, d.code());
        assertEquals("/ping", d.schemaPointer().orElseThrow());
        assertTrue(d.schemaPosition().isPresent(), "and carries the line the author can open");
        assertTrue(d.message().contains("no bound Java class for 'notification'"), d::message);
    }

    /** The contrast: a name the governing meta does not declare is the author's error, in either position. */
    @Test
    void anAuthorErrorIsTheSameWhereverItIsWritten() {
        assertEquals(Diagnostic.Code.SCHEMA_ERROR,
                only(ORDINARY_HEADER + "{\n@nosuchthing foo => text\n}\n").code());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR,
                only(ORDINARY_HEADER + "{\nfoo => @nosuchthing text\n}\n").code());
    }
}

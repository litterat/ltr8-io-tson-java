package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.10's <b>two parameter kinds, inferred by use</b> ({@code ParameterKinds}) -- and what the
 * kinds are for: an argument is classified by the parameter it binds, not by the shape of the token that
 * spells it.
 *
 * <p>§12.1 decides the channel by token shape, so an unquoted non-numeric argument arrives as a reference.
 * That is the right default with nothing else known, but §5.10 has the argument "read by the position it
 * lands in", and once the parameter's kind is inferred that position is known at the application. The kind
 * comes from the declared type of the slot the parameter stands in, read from the constructor's own
 * vocabulary -- which §9 makes general rather than a table of kernel names, since a slot holding a type
 * reference MUST be typed {@code type_ref}.
 */
class ParameterKindTest {

    private static final String ID = "https://example.test/kinds.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/kinds.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
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
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static String rejected(String declarations) {
        return assertThrows(TsonSchemaValidationException.class, () -> compile(declarations)).getMessage();
    }

    /** An entry's body with every {@code REFERENCE} hop followed. */
    private static Top bodyOf(TsonCompiledSchema compiled, String name) {
        Top body = compiled.schema().entries().get(name).body();
        while (body instanceof Reference reference) {
            body = compiled.schema().entries().get(reference.target().name()).body();
        }
        return body;
    }

    /**
     * §5.10's own example, verbatim: "in an enum's member list it is a member -- {@code e => <M> !enum [a b
     * M]} applied as {@code e<c>} admits {@code c} without any special spelling".
     *
     * <p>{@code enum.members} is a set of {@code identifier}, an atom, so {@code M} is a value parameter and
     * {@code c} is the token it always was. Before the kinds existed the argument stayed on the reference
     * channel and the linker asked the namespace for a type called {@code c}.
     */
    @Test
    void anArgumentBindingAValueParameterIsAMemberRatherThanAReference() {
        TsonCompiledSchema compiled = compile("""
                  e   => <M> !enum { members: [a b M] }
                  use => e<c>""");

        assertEquals(List.of("a", "b", "c"),
                assertInstanceOf(EnumBody.class, bodyOf(compiled, "use")).members());
    }

    /**
     * And the classification reaches {@code source}, which is what the linker validates: an argument recorded
     * as a value is not a name anything looks up. §8.2 records a value argument "as written", so the token
     * keeps its spelling.
     */
    @Test
    void aValueArgumentIsRecordedAsAValueInTheInstantiationSource() {
        TsonCompiledSchema compiled = compile("""
                  e   => <M> !enum { members: [a b M] }
                  use => e<c>""");

        String instantiation = ((Reference) compiled.schema().entries().get("use").body()).target().name();
        TypeArgument argument = compiled.schema().entries().get(instantiation).source().orElseThrow()
                .arguments().getFirst();

        assertEquals("c", assertInstanceOf(TypeArgument.Value.class, argument).value().text());
    }

    /** A parameter at a {@code type_ref} slot stays a type parameter, and its argument stays a reference. */
    @Test
    void anArgumentBindingATypeParameterIsStillAReference() {
        TsonCompiledSchema compiled = compile("""
                  arr => <T> !array { element_type: T }
                  use => arr<int32>""");

        String instantiation = ((Reference) compiled.schema().entries().get("use").body()).target().name();
        TypeArgument argument = compiled.schema().entries().get(instantiation).source().orElseThrow()
                .arguments().getFirst();

        assertEquals("int32", assertInstanceOf(TypeArgument.Ref.class, argument).ref().name());
    }

    /**
     * §5.10 binds a value parameter to scalars only and a type parameter to references, so a parameter
     * standing for a whole collection is neither -- and is refused where it is <b>declared</b>. It used to be
     * caught only when applied, as the substituted body failing its constructor's own vocabulary ("expected
     * an array for 'members'"), which is a verdict on the application rather than on the template that can
     * never have one.
     */
    @Test
    void aParameterStandingForACollectionIsRefusedAtTheDeclaration() {
        for (String declaration : List.of("  bad => <T> !enum { members: T }",
                                           "  bad => <T> !record { fields: T }")) {
            String message = rejected(declaration);

            assertTrue(message.contains("parameter 'T' stands where"), message);
            assertTrue(message.contains("neither a type reference nor a scalar"), message);
        }
    }

    /**
     * §5.10: "A parameter used in both kinds of position is a resolver error at the declaration." {@code T}
     * is a type parameter at {@code v} and a value parameter at {@code w}'s default, and no argument can be
     * both. Reported at the declaration, with nothing applying it.
     */
    @Test
    void aParameterUsedAsBothATypeAndAValueIsRefusedAtTheDeclaration() {
        String message = rejected("  both => <T> { v: T  w: int32 ~ T }");

        assertTrue(message.contains("parameter 'T' stands in both a type position and a value position"),
                message);
    }

    /**
     * A parameter with no concrete use anywhere in its cycle is a <b>type</b> parameter, and the assignment
     * is forced rather than chosen: being a value parameter means standing in a scalar slot, which is exactly
     * what would have grounded it. So {@code weird => <T> [weird<T>]} -- {@code T} passed only to the
     * template it is declared on -- classifies, and its argument stays a reference.
     *
     * <p>[TSON-SCHEMA] §5.10 makes such a parameter a resolver error instead. Reading it as forced is what
     * leaves {@code loop => <T> loop<T>} to be judged on what is actually wrong with it: a reference
     * template's body <em>is</em> the application, so there is no second slot a concrete use could go in, and
     * the useful verdict is that it applies itself forever.
     */
    @Test
    void aParameterGroundedOnlyByRecursionIsATypeParameter() {
        TsonCompiledSchema compiled = compile("""
                  weird => <T> [weird<T>]
                  use   => { w: weird<text> }""");

        String instantiation = ((RecordBody) compiled.schema().entries().get("use").body())
                .fields().getFirst().type().name();
        TypeArgument argument = compiled.schema().entries().get(instantiation).source().orElseThrow()
                .arguments().getFirst();

        assertEquals("text", assertInstanceOf(TypeArgument.Ref.class, argument).ref().name());
    }

    /** And the self-applying reference template is judged on the loop, not on its parameter's kind. */
    @Test
    void aSelfApplyingReferenceTemplateStillReportsTheLoop() {
        assertTrue(rejected("  loop => <T> loop<T>\n  holder => { p: loop<text> }")
                .contains("reference template whose own body applies it again"));
    }

    /**
     * An application closed <b>on demand</b> classifies too. A composition supertype has to absorb the closed
     * entry's fields during resolution's own driving loop, before the batch inference pass can run, so the
     * materialiser infers that one template's kinds itself -- the template has resolved by then, which is all
     * the walk needs.
     */
    @Test
    void anApplicationClosedOnDemandClassifiesItsArgumentsToo() {
        TsonCompiledSchema compiled = compile("""
                  st => !enum [red green]
                  t  => <M> { a: st ~ M }
                  x  => t<red> & { b: text }""");

        TypeDefinition composed = compiled.schema().entries().get("x");
        assertEquals(List.of("a", "b"), ((RecordBody) composed.body()).fields()
                .stream().map(io.ltr8.tson.schema.meta.RecordField::name).toList());
    }
}

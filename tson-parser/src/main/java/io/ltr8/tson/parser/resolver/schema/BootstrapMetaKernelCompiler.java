package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;

import java.util.Optional;

/**
 * Produces the real {@link Top} object for one of meta-kernel's own {@code Instance} declarations
 * -- deliberately not a general, schema-driven reader. Meta-kernel's own {@code !!meta} names
 * itself (Part 2 §1.5's "one deliberate circularity"), so at the point this class runs, no compiled
 * reader can exist yet for anything meta-kernel itself declares -- there is no external namespace to
 * compile against (unlike meta.tn1/core.tn1, each governed by an already-resolved rung below them),
 * and even meta-kernel's own in-progress state isn't safe to compile a reader from generically:
 * {@code enum => ~atom & { members: set<token> } }'s own field type is an argument-bearing
 * {@code type_ref} that only ever gets instantiated by {@code SchemaValidator}'s own materialization
 * pass, which meta-kernel -- the thing that pass would normally run *over* -- has never been
 * through. Trying to make bootstrap route through the same general machinery as everything else
 * would mean solving that materialization-during-bootstrap problem for no real benefit: meta-kernel
 * itself only ever instantiates its own constructors in exactly two shapes (confirmed directly
 * against the real fixture, not assumed) --
 *
 * <ul>
 *   <li>a bare {@code {}} (five real cases: {@code value => !unit {}}, {@code token => !unit {}},
 *   {@code void => !unit {}}, {@code integer => !integer_type {}}, {@code text => !text_type {}},
 *   {@code uri => !uri_type {}}, {@code regex => !regex_type {}} -- six constructors, seven
 *   declarations), where the target's own {@code UNCONSTRAINED} constant (or, for {@code unit},
 *   which declares no fields at all, a bare {@code new Unit()}) is already exactly the right
 *   value, or</li>
 *   <li>a bare array of tokens (six real cases: {@code boolean}/{@code product_access_type}/{@code
 *   product_size_type}/{@code field_state}/{@code element_state}/{@code type_kind}, all
 *   {@code !enum [...]}), read via {@link MetaKernelParser#toEnumBody} directly off the token text,
 *   bypassing base type identification entirely (the same reason that method exists at all --
 *   {@code "true"}/{@code "false"} would otherwise be misidentified as real TSON booleans before
 *   {@code EnumBody.members} ever saw them).</li>
 * </ul>
 *
 * <p>So the simplest, most direct thing this class can do is exactly that: a closed switch over
 * meta-kernel's own six real constructor names, each branch doing the one, known-correct
 * construction by hand -- no {@code TsonSchemaParser}, no {@code ParserFactoryRegistry}, no
 * materialization, no {@code TsonMapperReader}, nothing generic at all. This is what "the bootstrap
 * compiler can do whatever tricks it needs to" concretely means here: we already know, from the real
 * fixture, exactly what shape every one of meta-kernel's own instances has, so there's no reason to
 * reach for a mechanism built for the *general*, not-known-in-advance case.
 */
final class BootstrapMetaKernelCompiler {

    private BootstrapMetaKernelCompiler() {
    }

    /** {@link Optional#empty()} for any target outside the six real constructor names above -- left for the caller to decide what that means (today: the declaration is simply left out of the result, matching {@link MetaKernelParser}'s own established behavior for an unrecognized target). */
    static Optional<Top> compile(Instance instance) {
        return switch (instance.target()) {
            case "unit" -> {
                requireEmptyBody(instance);
                yield Optional.of(new Unit());
            }
            case "integer_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(IntegerType.UNCONSTRAINED);
            }
            case "text_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(TextType.UNCONSTRAINED);
            }
            case "uri_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(UriType.UNCONSTRAINED);
            }
            case "regex_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(RegexType.UNCONSTRAINED);
            }
            case "enum" -> Optional.of(MetaKernelParser.toEnumBody(instance.value()));
            default -> Optional.empty();
        };
    }

    /** Every empty-bodied target above is only ever instantiated as a bare {@code {}} in the real fixture -- checked rather than assumed, since each one's own constraint value is a hand-picked constant, not parsed from the instance body. */
    private static void requireEmptyBody(Instance instance) {
        if (!(instance.value().coreValue() instanceof EmptyBrace)) {
            throw new IllegalStateException(
                    "expected {} for !" + instance.target() + ", found " + instance.value().coreValue());
        }
    }
}

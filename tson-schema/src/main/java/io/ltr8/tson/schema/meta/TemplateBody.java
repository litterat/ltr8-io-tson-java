package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Set;

/**
 * The body of a template -- an entry declaring type parameters, which §5.10 calls open -- held in the form it
 * was written rather than resolved into constructor vocabulary. <b>It holds in both directions</b>: a
 * {@link TypeDefinition#body} that is one of these means the entry declares {@link
 * TypeDefinition#parameters}, and every entry that declares parameters has one. §5.10's partial application
 * was the last exception -- {@code <B> pair<uuid, B>} is the {@code !reference { target: pair<uuid, B> }}
 * §8.1 says it denotes, spellable since {@code reference.target} became a {@code type_ref}.
 *
 * <p><b>Why the body is held rather than quoted.</b> A slot that holds names can hold a parameter for free,
 * because a parameter is a name -- {@link TypeRef#name} may be one, at any depth, so {@code [T]},
 * {@code ( T | error )} and {@code [T, text]} need no representation of their own. Only an immediate value
 * slot ({@code min_items: N}, {@code format: F}) cannot, and a vocabulary that quotes every slot kind
 * uniformly to cover those few is incomplete wherever the vocabulary it quotes recurses: it has no case for a
 * collection, so a parameter inside {@code variants}/{@code elements}/{@code members} has nowhere to go.
 * Holding the written form instead makes substitution one rule at every depth -- rewrite the tokens that
 * resolve into {@code parameters} -- and defers binding against constructor vocabulary to materialisation,
 * the one moment it is decidable. {@code SPEC-FEEDBACK.md} #5 carries the full argument; this is a deliberate
 * divergence from Revision 33's {@code instance_template}, implemented here as proof for the next revision.
 *
 * <p><b>Why it is an interface declared here.</b> The held form is {@code tson-compiler}'s own schema AST, and
 * {@code tson-compiler} depends on {@code tson-schema}, not the reverse -- so this module declares the seat
 * and the depending module fills it, the same arrangement {@link SourcePosition} has with {@code Position}.
 * It is {@code non-sealed} for that reason alone and is <b>not</b> an extension point: unlike {@link Data},
 * whose implementations are consumer classes this library has never seen, exactly one class implements this,
 * and it lives one module up.
 *
 * <p><b>It has no name of its own, by construction.</b> The implementation carries {@code @Transparent}, so
 * a written body is the application it holds and the carrier appears nowhere in it -- there is no type-ref
 * for a name to occupy, and one declared here would be inert in any case ({@code tson-bind} reads a class's
 * own annotations, not its interface's). Whether an open body <em>is</em> a {@code !template} -- a type the
 * kernel would declare, with this as its bound class -- remains open ({@code SPEC-FEEDBACK.md} #5), but what
 * a body <em>writes</em> no longer waits on it.
 *
 * <p><b>It never serialises.</b> An open entry's resolved form is its declaration round-tripped, not a
 * {@code type_definition} value -- which could not carry it in any case, the kernel declaring {@code body:
 * top} REQUIRED with no {@code top} an open body could be. So no implementation carries {@code @Typename},
 * nothing binds through it, and a resolved-output consumer never meets one (§1.3).
 *
 * <p><b>What it is opaque to, and what it is not.</b> A held body withholds one thing: what a reference
 * <em>resolves to</em>, which no argument settles until substitution supplies them. So type-kind validation
 * and inhabitance apply to it at materialisation. What does not need that is asked here instead, through the
 * two questions below: §5.10's unreferenced-parameter rule off {@link #names()}, and §5.10.1's regularity
 * rule and §5.10's arity rule off {@link #applications()} -- arity counting parameters the <em>referenced</em>
 * entry declares, which holding never hid.
 *
 * <p><b>The two questions are not interchangeable, and a check must pick the right one.</b> {@link
 * #applications()} returns a distinguishable shape; {@link #names()} returns every token the body holds, type
 * references and field names and literals alike. So a rule that must not fire on a field name -- "this token
 * names an unapplied template" -- has no sound form here and belongs downstream, on the entry materialisation
 * mints.
 *
 * <p><b>It is content, so it participates in equality.</b> Unlike {@link TypeDefinition}'s {@code position},
 * which is excluded so that two parses of one declaration compare equal, two templates with different bodies
 * are different templates.
 */
public non-sealed interface TemplateBody extends Top {

    /**
     * Every unquoted name this body mentions, at any depth -- the one question about a held body that can be
     * answered without resolving it, and the one §5.10 asks at link time: a declared parameter the body never
     * references is an author error.
     *
     * <p><b>Declared rather than discovered</b>, as {@link Data#references()} is and for the same reason: the
     * carrier's shape is the implementing module's business. What it must return is exactly the set of tokens
     * substitution would rewrite -- unquoted ones, a quoted token in a value slot being a literal and never a
     * name -- or the check and the rewrite disagree about what a parameter reference is.
     */
    Set<String> names();

    /**
     * Every type application this body writes, at any depth -- the second question a held body answers
     * without being resolved, and the one §5.10.1 asks at the declaration: a recursive application that does
     * not pass its parameters through unchanged grows its argument at every level, so no finite set of types
     * closes it.
     *
     * <p><b>Why the check cannot simply wait for materialisation.</b> A template nobody applies is never
     * materialised, so deferring the rule would let an irregular one ship with no verdict at all; and an
     * application that does reach materialisation hits a depth backstop instead, which reports a chain of
     * derived names against the line that used the template rather than the line that contains the mistake.
     *
     * <p><b>Declared rather than discovered</b>, as {@link #names()} is: a held body is wire form, and which
     * of its records spell {@code type_ref}'s two-member shape is the implementing module's business. Nesting
     * is the caller's -- what this returns is every application the tree holds, an application inside
     * another's argument list included, each as the {@link TypeRef} it spells.
     *
     * <p><b>This is the precise half of what a held body knows about its references.</b> An application has a
     * shape nothing else in the tree shares, so what comes back is references and only references -- where
     * {@link #names()} cannot separate a type reference from a field name. A check that would be wrong about
     * a field name must ask this one.
     */
    List<TypeRef> applications();
}

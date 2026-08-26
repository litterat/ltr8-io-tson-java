package io.ltr8.tson.schema.meta;

import java.util.Set;

/**
 * The body of a template -- an entry declaring type parameters, which §5.10 calls open -- held in the form it
 * was written rather than resolved into constructor vocabulary. One direction only: a {@link
 * TypeDefinition#body} that is one of these means the entry declares {@link TypeDefinition#parameters}, but
 * not every template has one -- a partial application ({@code <B> pair<uuid, B>}) keeps the {@code type_ref}
 * with arguments it already resolves to, a parameter in an argument being an ordinary name on the reference
 * channel.
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
 * <p><b>It never serialises.</b> An open entry's resolved form is its declaration round-tripped, not a
 * {@code type_definition} value -- which could not carry it in any case, the kernel declaring {@code body:
 * top} REQUIRED with no {@code top} an open body could be. So no implementation carries {@code @Typename},
 * nothing binds through it, and a resolved-output consumer never meets one (§1.3). What it is opaque to is
 * equally deliberate: the linker cannot walk a held body, so reference validation, inhabitance, and §5.10.1's
 * regularity rule apply to it at materialisation instead of at link time.
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
}

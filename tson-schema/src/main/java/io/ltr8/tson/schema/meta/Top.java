package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's structural root, {@code top => abstract {}} (Part 2 §4.1) -- every constructor IS-A it -- and
 * in Java what a schema map's entries are: {@code schema => {type_name => declared}}, an entry being its value
 * (Part 2 §8.1), a body a constructor built or a {@link ValueEntry}. A marker interface, not a
 * class (Java records can't extend a class, only implement interfaces), added purely to replicate
 * the kernel's own composition chain as real Java subtyping: {@code type => top & {}} becomes
 * {@link Type}, and {@code atom => type & {}}, {@code product => type & { ... }} and
 * {@code sum => type & {}} become {@link Atom}/{@link Product}/{@link Sum} each {@code extends Type};
 * {@code alias => top & { target: type_kind } } (which composes with {@code top} directly, not
 * through {@code type}) becomes {@link Alias} implementing this interface directly. Lets a consumer
 * test kind ancestry with an ordinary {@code instanceof Type}/{@code instanceof Product} rather than
 * switching on {@link EntryKind} by hand, and also lets {@code ltr8-bind}'s generic writer/reader dispatch on this same
 * sealed hierarchy directly for {@code !record}/{@code !array}/etc. type-refs -- {@code
 * DefaultUnionBinder} recurses through a multi-level sealed hierarchy like this one (a permitted
 * subclass that's itself sealed is flattened, not left as an unusable "member"), which is what
 * makes binding straight against {@code Top}/{@link Atom} practical at all (see {@code ltr8-bind}'s
 * own README "Under development" history for the bug this fixed).
 *
 * <p>{@link TemplateBody} describes something other than a constructed value, and composes with {@code top}
 * directly for that reason: the held body of an entry that declares type parameters, an ordinary record carrying
 * the application as text.
 *
 * <p>{@link ValueEntry} is the one branch that is not built by a constructor: an entry whose value is an instance of
 * an ordinary type, {@code orders => !api { ... }} or {@code create_order => !operation { ... }}. It is a value,
 * never a type, and is how a schema puts vocabulary of its own in a schema map. Every branch is sealed, so an
 * entry's kind is decidable by inspection and every switch over them is exhaustive.
 */
public sealed interface Top permits Type, Alias, TemplateBody, ValueEntry {
}

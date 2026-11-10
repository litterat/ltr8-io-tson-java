package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;

import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;
import java.util.Optional;

/**
 * Two imports' entries under one name that are one synthetic ([TSON-SCHEMA] §8.2): the rule both import merges apply,
 * the resolver's ({@code SchemaResolver.mergeImports}) and the linker's ({@code TsonSchemaLinker.mergeImports}).
 *
 * <p><b>A synthetic is identified by its form, not by the document that minted it.</b> Its name is derived from the
 * form alone, so two documents that write {@code [text]} without importing each other each mint {@code
 * array_text_…}, and a schema importing both reaches the one type twice. That is unification, as one schema reached
 * by two routes is, and not §2.2.3's collision of two declarations: the first arrival is kept, its declaring schema
 * stays its origin, and every use in either document names it. A name only one side marks synthetic is a declared
 * entry meeting a minted one, and stays the collision.
 *
 * <p><b>The two bodies must agree, and a disagreement is an internal invariant break.</b> Both minting sites render
 * the form canonically before deriving the name, and the names a form references are themselves one entry each in
 * the merged namespace (or collide on their own), so equal names mean equal forms. Two different bodies under one
 * minted name are the same broken naming function {@link MintedNames#claim} refuses within one phase, now met across
 * two documents: {@link IllegalStateException}, neither the author's error nor a gap. Each route's link credits
 * {@code subtypes} against its own view of the namespace, so the comparison leaves them out.
 *
 * <p><b>A document's own lift is held to the same rule</b> ({@link #requireForm}). A sugar form whose derived name an
 * import already declares references the import's entry rather than declaring its own, so the import's entry must be
 * a synthetic of that form: a declared entry of the name is §2.2.3's collision, and a synthetic of another form is
 * the broken naming function again. There the arriving side is this document's form resolved on its own, before any
 * link, so the comparison leaves out what only a link or the whole schema's resolution derives: the chain, and a
 * choice's {@code disjoint}.
 */
public final class ImportedSynthetics {

    private ImportedSynthetics() {
    }

    /**
     * Whether {@code name}'s incumbent and arriving entries, declared by two different schemas, are one synthetic.
     *
     * @return {@code true} when both are synthetic, so the arrival unifies with the incumbent; {@code false} when
     *         either is a declared entry, which the caller refuses as the collision it is
     * @throws IllegalStateException if both are synthetic and their bodies differ
     */
    public static boolean areOne(String name, TsonSchema.EntryFacts incumbentFacts, Top incumbent,
                                 String incumbentOrigin, TsonSchema.EntryFacts arrivingFacts, Top arriving,
                                 String arrivingOrigin) {
        if (!incumbentFacts.synthetic() || !arrivingFacts.synthetic()) {
            return false;
        }
        if (!withoutSubtypes(incumbent).equals(withoutSubtypes(arriving))) {
            throw new IllegalStateException("'" + incumbentOrigin + "' and '" + arrivingOrigin + "' each mint the "
                    + "internal name '" + name + "' for a different form ([TSON-SCHEMA] §8.2 derives the name from "
                    + "the form alone): " + incumbent + " and " + arriving);
        }
        return true;
    }

    /**
     * Holds {@code imported}, the entry an import declares under {@code name}, to {@code own}, the form this
     * document's lift derived {@code name} from, resolved on its own.
     *
     * @throws SchemaValidationException if the import's entry is a declared one, the collision of a declared entry
     *         and a minted one
     * @throws IllegalStateException if it is a synthetic of a different form
     */
    public static void requireForm(String name, TsonSchema.EntryFacts importedFacts, Top imported,
                                   String importedOrigin, Top own) {
        if (!importedFacts.synthetic()) {
            throw new SchemaValidationException("'" + name + "', the internal name this schema's sugar form "
                    + "derives, is declared by '" + importedOrigin + "' as an entry of its own, which collides with "
                    + "it ([TSON-SCHEMA] §2.2.3, §8.2)");
        }
        if (!form(imported).equals(form(own))) {
            throw new IllegalStateException("'" + importedOrigin + "' mints the internal name '" + name + "' for "
                    + "a different form than this schema's ([TSON-SCHEMA] §8.2 derives the name from the form "
                    + "alone): " + imported + " and " + own);
        }
    }

    /** What a lift's form fixes of an entry: its body, without its chain or a choice's derived {@code disjoint}. */
    private static Object form(Top value) {
        Top bare = TypeChain.withoutChain(value);
        return switch (bare) {
            case ChoiceBody choice -> new ChoiceBody(choice.chain(), choice.variants(), Optional.empty());

            default -> bare;
        };
    }

    private static Top withoutSubtypes(Top value) {
        return TypeChain.subtypesOf(value).isEmpty() ? value
                : TypeChain.withChain(value, TypeChain.chainOf(value).withSubtypes(List.of()));
    }
}

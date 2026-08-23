package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;

import java.util.Optional;

/**
 * Object-binding mode's own record-subtype/union dispatcher -- the {@code DataClassUnion}-bounded
 * counterpart to {@code reader.VariantParser}. That class dispatches by {@code !typeName} over
 * {@code TypeDefinition.subtypes()} -- purely schema-derived, with each subtype's own target Java
 * class found by convention via {@code DataNameBinder} once dispatch reaches {@code resolver}. This
 * class exists for the same reason {@link RecordBindReader} exists alongside {@code
 * reader.RecordParser}: a caller with an *independently designed* Java sealed interface (not
 * necessarily named/structured the way the schema-driven binder convention would derive it) needs
 * dispatch bounded by that real Java type, the same way {@link RecordBindReader} takes a real {@code
 * DataClassRecord} instead of deriving one by convention.
 *
 * <p><b>Record-scoped, deliberately, for now.</b> {@code TypeDefinition.subtypes()} is non-empty for
 * any composed kind with real subtypes, not just records ({@code reader.VariantParser} handles
 * array's own {@code set}/{@code array_min}/{@code array_max}/{@code array_ranged} too) -- but those
 * aren't meant to be dispatched to by an explicit {@code !array_min [...]} type-ref the way a record
 * subtype is; whether/how the spec should even allow that is still open. Only {@link
 * RecordBindReader.Factory} constructs this class, and only for a record-shaped declaration with
 * real subtypes; {@link ArrayBindReader}/{@link MapBindReader}/{@link TupleBindReader} always read
 * as themselves, no dispatch layer at all.
 *
 * <p><b>Preserves {@code reader.VariantParser}'s own "own body is a valid reading too" rule</b> --
 * a value with no type-ref, or one naming this declaration itself, reads via {@code ownParser}
 * unconditionally; only a value naming something else dispatches at all. Whether {@code ownParser}
 * is ever actually reachable depends on what {@code name} binds to: {@link RecordBindReader.Factory}
 * supplies a real {@link RecordBindReader} when the schema declaration has its own real fields *and*
 * a real bound Java record exists for it, or a reader that unconditionally throws when {@code name}
 * binds to a pure marker interface with no data of its own (e.g. {@code top}/{@code atom} -- see
 * that factory's own Javadoc).
 *
 * <p><b>Membership is checked against {@code descriptor.memberTypes()} fresh on every read, not
 * precomputed at construction</b> -- unlike almost everything else in this package. {@link
 * DataClassUnion} explicitly supports a non-sealed union growing at runtime ({@code
 * addMemberType}, e.g. as more implementations get classloaded); a construction-time snapshot would
 * silently go stale for exactly that case. A union's own member count is small (a handful of
 * variants, not hundreds of record fields), so the linear scan this trades for isn't the kind of
 * per-read cost the rest of this package was built to avoid.
 *
 * <p>Matches {@code TsonObjectReader.resolveUnionMember}'s own two-pass precedence exactly (an exact
 * {@link Typename} match first, then a case-insensitive simple-class-name match for an un-annotated
 * member) -- except this only needs to confirm membership, not resolve to a {@code Class}: once a
 * type-ref is confirmed a real member, the actual read dispatches by schema name through {@code
 * resolver}, the same compiled-schema path every other dispatch in this codebase uses, not by
 * reflectively constructing the member class directly the way {@code TsonObjectReader} does.
 *
 * <p><b>The type-ref driving this decision is read without being consumed</b> ({@link
 * EventSkip#typeRefAhead}), so the reader dispatched to is handed the whole data-value -- its annotations,
 * its type-ref and its core-value -- exactly as it would be if nothing had dispatched to it. Consuming the
 * framing here instead, which is what reaching past the annotations to the type-ref used to require, left
 * the reader that actually builds the value unable to see annotations written on it.
 */
final class VariantBindReader implements TsonTypeReader<Object> {

    private final String name;
    private final TsonTypeReader<?> ownParser;
    private final DataClassUnion descriptor;
    private final TsonTypeReaderResolver resolver;

    /** A schema type name to the Java class bound to it, or {@code null} where nothing is -- see {@link #isMember}. */
    private final java.util.function.Function<String, Class<?>> boundClass;

    VariantBindReader(String name, TsonTypeReader<?> ownParser, DataClassUnion descriptor,
                      TsonTypeReaderResolver resolver, java.util.function.Function<String, Class<?>> boundClass) {
        this.name = name;
        this.ownParser = ownParser;
        this.descriptor = descriptor;
        this.resolver = resolver;
        this.boundClass = boundClass;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isEmpty() || typeRef.get().equals(name)) {
            return ownParser.read(ctx);
        }
        String ref = typeRef.get();
        if (!isMember(ref)) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "'" + ref + "' is not a member of the union '" + name
                            + "' binds against " + describeMembers(),
                    "one of " + describeMembers(), ref);
            EventSkip.dataValue(ctx); // framing included: nothing consumed it, this value being unreadable
            return null;
        }
        return resolver.resolve(ref).read(ctx);
    }

    /**
     * Whether {@code typeRef} names one of the union's members.
     *
     * <p><b>The bind context is asked first, and it is the authority.</b> A schema name reaches a class
     * through the read's own {@code DataNameBinder}, which is where a name that is not the class's own is
     * recorded -- {@code set}, {@code array_min}, {@code array_max}, {@code array_ranged} and {@code vector}
     * all bind to {@code ArrayBody}, §5's array family resolving to one body shape. Matching the name
     * against {@code @Typename} and simple class names alone cannot see any of that, so a conforming {@code
     * !set} body was refused as "not a member of the union 'top'" by the one part of the pipeline that had
     * not been told what the binder knows.
     *
     * <p>The name passes are kept behind it, unchanged: they are {@code TsonObjectReader.resolveUnionMember}'s
     * own two-pass precedence, and they are what a consumer's union gets when its members are not registered
     * under schema names at all.
     */
    private boolean isMember(String typeRef) {
        Class<?> bound = boundClass.apply(typeRef);
        if (bound != null) {
            for (Class<?> member : descriptor.memberTypes()) {
                if (member == bound) {
                    return true;
                }
            }
        }
        for (Class<?> member : descriptor.memberTypes()) {
            Typename tn = member.getAnnotation(Typename.class);
            if (tn != null && tn.name().equals(typeRef)) {
                return true;
            }
        }
        for (Class<?> member : descriptor.memberTypes()) {
            if (member.getAnnotation(Typename.class) == null && member.getSimpleName().equalsIgnoreCase(typeRef)) {
                return true;
            }
        }
        return false;
    }

    private String describeMembers() {
        Class<?>[] members = descriptor.memberTypes();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(members[i].getSimpleName());
        }
        return sb.append(']').toString();
    }
}

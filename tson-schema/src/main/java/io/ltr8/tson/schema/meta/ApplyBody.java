package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Objects;

/**
 * The kernel's {@code meta.apply}: an application, {@code box<T>}, written in place ([TSON-SCHEMA] §5.10). A reference
 * holds a name only, so an application is a construction of its own: {@code head} names what is applied, and {@code
 * arguments} supply it, positionally.
 *
 * <p><b>Where one stands.</b> In resolved output, only inside an open record, at a type slot or among its parents,
 * with one of the record's parameters among its arguments and an open record as its head: every other application
 * has closed, a constructor's to its construction and an open record's to its record. It is also the value of the
 * {@code meta.application} annotation, where every argument is a name. While a schema resolves it stands wherever an
 * author wrote {@code head<args>}, and an argument may then be an application in turn, written in place, which a
 * constructor's construction lands at its slot; an open record's argument is a name or a parameter.
 *
 * <p><b>No kind of its own.</b> It composes {@code meta.type} directly, being none of an atom, a product or a sum: it
 * stands for a type that exists only once its open record is applied, and nothing reads a value against it.
 *
 * <p>Identity is the head and the arguments, compared as values (§8.2), so two applications written alike are one.
 */
@Typename(name = "meta.apply")
public record ApplyBody(TypeChain chain, TypeRef head, List<TypeExpr> arguments) implements Type {

    @Record
    public ApplyBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(head, "head");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("an application has at least one argument: " + head.name());
        }
    }

    /** {@code head} applied to {@code arguments}, placed in no chain. */
    public ApplyBody(TypeRef head, List<? extends TypeExpr> arguments) {
        this(TypeChain.EMPTY, head, List.copyOf(arguments));
    }

    /** {@code head} applied to {@code arguments}, each a name. */
    public static ApplyBody of(String head, String... arguments) {
        return new ApplyBody(TypeRef.of(head), List.of(arguments).stream().map(TypeRef::of).toList());
    }

    /** This application written in place, as it stands at a slot. */
    public TypeInline inPlace() {
        return new TypeInline(this);
    }

    /** The arguments that are names or parameters, in order; empty where any is written in place. */
    public List<TypeRef> names() {
        return arguments.stream().allMatch(TypeRef.class::isInstance)
                ? arguments.stream().map(TypeRef.class::cast).toList() : List.of();
    }

    /** {@code head} with the same arguments: how an alias hop is followed. */
    public ApplyBody withHead(TypeRef head) {
        return new ApplyBody(chain, head, arguments);
    }

    /** The same head over {@code arguments}. */
    public ApplyBody withArguments(List<? extends TypeExpr> arguments) {
        return new ApplyBody(chain, head, List.copyOf(arguments));
    }

    @Override
    public ApplyBody withChain(TypeChain chain) {
        return new ApplyBody(chain, head, arguments);
    }

    /** By head and arguments; the chain is always empty, an application carrying none. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ApplyBody other && head.equals(other.head) && arguments.equals(other.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(head, arguments);
    }

    /**
     * The source spelling, for a message: the head, then {@code <}, each argument's text separated by one space, and
     * {@code >} -- {@code pair<text integer>}, a nested application spelled the same way.
     */
    public String text() {
        StringBuilder out = new StringBuilder(head.name()).append('<');
        for (int i = 0; i < arguments.size(); i++) {
            out.append(i == 0 ? "" : " ").append(arguments.get(i).text());
        }
        return out.append('>').toString();
    }
}

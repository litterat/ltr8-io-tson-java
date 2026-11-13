package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.schema.ApplicationRef;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Construction sugar on the written form ([TSON-SCHEMA] §5.3, §5.10): {@code !meta.apply { head: C  arguments: [a₁ …
 * aₙ] }} over a constructor {@code C} is rewritten to the binding record {@code !C { f₁: a₁ … fₙ: aₙ }} before anything
 * reads it, its argument slots ({@link ConstructionSugar#slots}) filled in order, innermost first. Every application
 * left is then kernel data: an open record's, its arguments names or parameters, the kernel's {@code meta.apply}
 * typing them {@code [meta.type_ref; 1..]}. So a payload is read once, under the kernel's own types.
 *
 * <p><b>Who wrote an application decides how much is rewritten.</b> One desugaring wrote from {@code head<args>} is the
 * author's sugar, nested applications and all: a constructor's is constructed, and an argument of any other that is
 * no name is refused (§5.3). One the author wrote out, {@code { of: !meta.apply { … } }}, means what the sugar means
 * where its arguments are names (§5.10), and is rewritten then; one whose arguments hold a type in place is left as
 * written, for the kernel's reader to refuse.
 *
 * <p><b>An open record's application orders nothing</b> (a name at a type position, [TSON-SCHEMA] §3.4.1): whether a
 * head is one is asked without making it ready ({@link DefinitionGetter#opens}), so {@code chain => <T> { next?:
 * chain<T> }} and mutual recursion wait on nothing. Any other head is made ready before it is asked whether it
 * constructs, as reading a construction against it would make it.
 *
 * <p>A value comes back as the same object where nothing in it changed, so what is keyed on a value desugaring wrote
 * still finds it.
 */
final class ConstructionRewrite {

    private static final String APPLY = KernelNames.of("apply");
    private static final String HEAD = "head";
    private static final String ARGUMENTS = "arguments";

    /** Where a head and a constructor's slots are found; asking for a declaration not yet ready makes it ready. */
    private final Function<String, Top> types;

    /** Whether a head, past its alias hops, is an open record, asked without waiting on it. */
    private final Predicate<String> opens;

    ConstructionRewrite(Function<String, Top> types, Predicate<String> opens) {
        this.types = types;
        this.opens = opens;
    }

    /**
     * {@code written} with each constructor's application in it written as its construction in place. {@code
     * desugared} says desugaring wrote the whole value.
     */
    DataValue inPlace(DataValue written, boolean desugared) {
        if (isApplication(written)) {
            return applied(written, desugared, true);
        }
        CoreValue core = switch (written.coreValue()) {
            case RecordValue record -> {
                List<RecordValue.Field> fields = new ArrayList<>(record.fields().size());
                boolean changed = false;
                for (RecordValue.Field field : record.fields()) {
                    DataValue value = inPlace(field.value(), desugared);
                    changed |= value != field.value();
                    fields.add(value == field.value() ? field : new RecordValue.Field(field.name(), value));
                }
                yield changed ? new RecordValue(fields) : record;
            }
            case ArrayValue array -> {
                List<DataValue> elements = new ArrayList<>(array.elements().size());
                boolean changed = false;
                for (DataValue element : array.elements()) {
                    DataValue value = inPlace(element, desugared);
                    changed |= value != element;
                    elements.add(value);
                }
                yield changed ? new ArrayValue(elements) : array;
            }
            case MapValue map -> {
                List<MapValue.MapEntry> entries = new ArrayList<>(map.entries().size());
                boolean changed = false;
                for (MapValue.MapEntry entry : map.entries()) {
                    DataValue value = inPlace(entry.value(), desugared);
                    changed |= value != entry.value();
                    entries.add(value == entry.value() ? entry : new MapValue.MapEntry(entry.key(), value));
                }
                yield changed ? new MapValue(entries) : map;
            }
            default -> written.coreValue();
        };
        return core == written.coreValue() ? written : new DataValue(written.annotations(), written.typeRef(), core);
    }

    /**
     * {@code generic}, written at a declaration's body ({@code x => meta.array<text>}), as the construction it spells,
     * tagged by its head as written; empty where its head is no constructor, its arguments then unjudged.
     */
    Optional<DataValue> body(ApplicationRef generic) {
        DataValue written = SchemaDesugarer.application(generic);
        DataValue constructed = applied(written, true, false);
        return constructed == written ? Optional.empty() : Optional.of(constructed);
    }

    /**
     * {@code generic}, written at a type slot ({@code f: meta.array<text>}), as the construction it spells, tagged by
     * its constructor past its alias hops; empty where its head is no constructor, every argument then a name.
     */
    Optional<DataValue> atSlot(ApplicationRef generic) {
        DataValue written = SchemaDesugarer.application(generic);
        DataValue constructed = applied(written, true, true);
        return constructed == written ? Optional.empty() : Optional.of(constructed);
    }

    /** The constructor {@code head} applies past its alias hops, or empty where it applies none (§5.3). */
    Optional<String> constructor(String head) {
        if (opens.test(head)) {
            return Optional.empty();
        }
        String constructor = AliasChain.terminal(head, types);
        return ConstructionSugar.constructs(constructor, types.apply(constructor)) ? Optional.of(constructor)
                : Optional.empty();
    }

    /**
     * One application: a constructor's as its construction, any other as written. A declaration's body is the one
     * construction not {@code inPlace}: it is tagged by the head as written, which its entry records as its
     * constructor, and no namespace type is refused there, nor an open record's argument, the declaration judging
     * that in its own order.
     */
    private DataValue applied(DataValue written, boolean desugared, boolean inPlace) {
        RecordValue apply = (RecordValue) written.coreValue();
        Optional<String> head = member(apply, HEAD).map(DataValue::coreValue).filter(TokenValue.class::isInstance)
                .map(token -> ((TokenValue) token).text());
        Optional<List<DataValue>> listed = member(apply, ARGUMENTS).map(DataValue::coreValue)
                .filter(ArrayValue.class::isInstance).map(array -> ((ArrayValue) array).elements());
        if (head.isEmpty() || listed.isEmpty()) {
            return written; // the kernel's reader refuses it in its own words
        }
        List<DataValue> arguments = listed.get();
        if (!desugared && !arguments.stream().allMatch(ConstructionRewrite::isName)) {
            return written; // the kernel's reader refuses an argument that is no name
        }
        Optional<String> constructor = constructor(head.get());
        if (constructor.isEmpty() && !inPlace) {
            return written;
        }
        List<DataValue> constructed = new ArrayList<>(arguments.size());
        for (DataValue argument : arguments) {
            constructed.add(isName(argument) ? argument : inPlace(argument, desugared));
        }
        if (constructor.isEmpty()) {
            for (DataValue argument : arguments) {
                if (!isName(argument)) {
                    throw TemplateMaterialiser.argumentNotAName(text(argument), suggestedName(argument),
                            text(written));
                }
            }
            return written;
        }
        RecordBody definition = (RecordBody) types.apply(constructor.get());
        if (inPlace) {
            ConstructionSugar.refuseNamespaceInPlace(text(written), head.get(),
                    arguments.stream().map(ConstructionRewrite::text).toList(), constructor.get(), definition, types);
        }
        List<String> slots = ConstructionSugar.slots(definition, types);
        ConstructionSugar.requireArity(head.get(), slots, constructed.size());
        List<Boolean> slotsInPlace = ConstructionSugar.slotsInPlace(definition, types);
        List<RecordValue.Field> fields = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            if (!isName(arguments.get(i)) && !slotsInPlace.get(i)) {
                throw TemplateMaterialiser.nameRequired(text(arguments.get(i)), suggestedName(arguments.get(i)));
            }
            fields.add(new RecordValue.Field(slots.get(i), constructed.get(i)));
        }
        return new DataValue(written.annotations(), Optional.of(inPlace ? constructor.get() : head.get()),
                new RecordValue(fields));
    }

    /** {@code generic} as its head applied to its arguments, each a name or a parameter. */
    static ApplyBody names(ApplicationRef generic) {
        return new ApplyBody(TypeRef.of(generic.name()), generic.args().stream()
                .map(argument -> TypeRef.of(((NameRef) argument).name())).toList());
    }

    /** Whether every argument of {@code generic} is a name or a parameter. */
    static boolean namesOnly(ApplicationRef generic) {
        return generic.args().stream().allMatch(NameRef.class::isInstance);
    }

    /** The refusal of {@code generic}, written where only a name may stand ({@link TemplateMaterialiser#nameRequired}). */
    static SchemaValidationException nameRequired(ApplicationRef generic) {
        DataValue written = SchemaDesugarer.application(generic);
        return TemplateMaterialiser.nameRequired(text(written), suggestedName(written));
    }

    /**
     * The refusal of {@code generic}'s first argument that is no name, {@code generic} applying an open record
     * ({@link TemplateMaterialiser#argumentNotAName}).
     */
    static SchemaValidationException argumentNotAName(ApplicationRef generic) {
        DataValue written = SchemaDesugarer.application(generic);
        DataValue argument = ((ArrayValue) member((RecordValue) written.coreValue(), ARGUMENTS).orElseThrow()
                .coreValue()).elements().stream().filter(each -> !isName(each)).findFirst().orElseThrow();
        return TemplateMaterialiser.argumentNotAName(text(argument), suggestedName(argument), text(written));
    }

    /** {@code generic} as the author wrote it, for a message ({@link #text(DataValue)}). */
    static String text(ApplicationRef generic) {
        return text(SchemaDesugarer.application(generic));
    }

    private static boolean isApplication(DataValue value) {
        return value.typeRef().filter(APPLY::equals).isPresent() && value.coreValue() instanceof RecordValue;
    }

    private static boolean isName(DataValue argument) {
        return argument.typeRef().isEmpty() && argument.coreValue() instanceof TokenValue;
    }

    /**
     * A type as the author wrote it at an application, for a message: a name, an application as {@code
     * head<argument argument>} with each argument spelled the same way, a type in place by its constructor.
     */
    static String text(DataValue written) {
        if (written.coreValue() instanceof TokenValue token) {
            return token.text();
        }
        if (!(written.coreValue() instanceof RecordValue record)) {
            return String.valueOf(written.coreValue());
        }
        if (!isApplication(written)) {
            return member(record, WireForm.OF).map(ConstructionRewrite::text)
                    .orElseGet(() -> "!" + written.typeRef().orElse("") + " { ... }");
        }
        StringBuilder out = new StringBuilder(member(record, HEAD).map(ConstructionRewrite::text).orElse("")).append('<');
        List<DataValue> arguments = member(record, ARGUMENTS).map(DataValue::coreValue)
                .filter(ArrayValue.class::isInstance).map(array -> ((ArrayValue) array).elements()).orElse(List.of());
        for (int i = 0; i < arguments.size(); i++) {
            out.append(i == 0 ? "" : " ").append(text(arguments.get(i)));
        }
        return out.append('>').toString();
    }

    /**
     * A name for a declaration of {@code written}, for a message, as {@link TemplateMaterialiser#suggestedName} names
     * an application: the head's last segment, then each argument's, {@code my} for one that is no name.
     */
    static String suggestedName(DataValue written) {
        DataValue application = written.coreValue() instanceof RecordValue wrapper && !isApplication(written)
                ? member(wrapper, WireForm.OF).orElse(written) : written;
        if (!isApplication(application)) {
            return "my_type";
        }
        RecordValue record = (RecordValue) application.coreValue();
        StringBuilder name = new StringBuilder(lastSegment(member(record, HEAD).map(ConstructionRewrite::text)
                .orElse("")));
        member(record, ARGUMENTS).map(DataValue::coreValue).filter(ArrayValue.class::isInstance)
                .ifPresent(array -> ((ArrayValue) array).elements().forEach(argument -> name.append('_')
                        .append(isName(argument) ? lastSegment(text(argument)) : "my")));
        return name.toString();
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Optional<DataValue> member(RecordValue record, String name) {
        return record.fields().stream().filter(field -> name.equals(field.name())).map(RecordValue.Field::value)
                .findFirst();
    }
}

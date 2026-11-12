package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

/**
 * How schema vocabulary is spelled as data -- the one place that knows what a reference and an application in a
 * slot look like on the wire, and the vocabulary's member names a binding record writes.
 *
 * <p><b>Why one class.</b> A binding record is written by two phases -- {@code SchemaDesugarer} rewriting a sugar
 * form into the construction it denotes, and {@code ConstructionSugar} spelling an applied constructor's arguments
 * -- and walked by a third, {@code ParameterTypes}, for where an open record's parameters stand. A second opinion
 * about what a reference looks like in a slot is what makes one of them wrong.
 *
 * <p><b>Nothing here is canonical output, and {@code DataClassObjectWriter} cannot serve any of it.</b> That
 * writer's output is canonical-explicit and fully quoted, which is a different language from the one a binding is
 * written in: a name is an unquoted token. What is written here is what an author would have written -- an unquoted
 * token where the writer would quote, an application as the data grammar's reference, and nothing at all where the
 * constructor's own default says it.
 */
final class WireForm {

    private WireForm() {
    }

    // ── The vocabulary's own member names ────────────────────────────────────────────────────────

    /** {@code meta.record_field.value}: §8.1's literal channel. */
    static final String VALUE = "value";

    /** {@code meta.record}: the constructor an open record applies. */
    static final String RECORD = KernelNames.of("record");

    static final String RECORD_FIELD = KernelNames.of("record_field");

    /** {@code meta.record.parameters}: an open record's parameters, each a {@code meta.param}. */
    static final String PARAMETERS = "parameters";
    static final String NAME = "name";

    static final String FIELDS = "fields";
    static final String TYPE = "type";
    static final String VOIDABLE = "voidable";

    /**
     * {@code record.name_type} -- the type a record's field names are values of ([TSON-SCHEMA] §5.2). Written
     * exactly where the record states one, so a binding keeps the difference between stating and not.
     */
    static final String NAME_TYPE = "name_type";

    /** {@code meta.type_inline.of}: the construction a type written in place holds, tagged with its constructor. */
    static final String OF = "of";

    // ── Building blocks ──────────────────────────────────────────────────────────────────────────

    /** A bare value in a field or element position -- no annotations and no type-ref of its own. */
    static DataValue plain(CoreValue value) {
        return plain(value, List.of());
    }

    /** The same, carrying the annotations written on the construct it stands for. */
    static DataValue plain(CoreValue value, List<Annotation> annotations) {
        return new DataValue(annotations, Optional.empty(), value);
    }

    static RecordValue.Field nameField(String name, String text) {
        return new RecordValue.Field(name, plain(new TokenValue(text, TokenForm.UNQUOTED)));
    }

    /** A reference in the wire spelling: its name, an unquoted token. */
    static CoreValue refValue(TypeRef ref) {
        return new TokenValue(ref.name(), TokenForm.UNQUOTED);
    }
}

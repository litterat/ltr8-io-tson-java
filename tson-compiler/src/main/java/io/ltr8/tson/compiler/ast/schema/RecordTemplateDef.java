package io.ltr8.tson.compiler.ast.schema;

import io.ltr8.tson.compiler.ast.DataValue;

import java.util.List;

/**
 * A record template as {@code SchemaDesugarer} normalises it ([TSON-SCHEMA] §5.2, §5.10): the declaration's
 * parameters and the {@code !meta.record_template { … }} payload its body denotes, each field labelled where the
 * parameters are in scope -- a bare parameter name in a field's type is its {@code type_param}, an unquoted parameter
 * token in its value its {@code value_param} -- and the parameters stated last, each provisionally a type parameter.
 *
 * <p><b>The one variant no parser builds.</b> Both spellings of a record template reach it -- the sugar body {@code
 * <T> { x: T }} and the explicit {@code <T> !meta.record { … }} -- and nothing an author writes does: a written
 * {@code !meta.record_template { … }} is an {@link Instance}, which the resolver refuses as resolver vocabulary
 * (§8.1). So the type, not a flag, is what tells the desugarer's own output from a construction an author wrote.
 *
 * <p>Every slot but the labels and the parameter list is carried as written, so the resolver still answers the
 * author's spelling in the author's terms: its bindings against {@code meta.record}'s vocabulary, and a parameter
 * standing anywhere but a field's type, a field's value or an application's argument (§5.10).
 */
public record RecordTemplateDef(List<String> typeParams, DataValue payload) implements TypeDef {

    public RecordTemplateDef {
        typeParams = List.copyOf(typeParams);
    }
}

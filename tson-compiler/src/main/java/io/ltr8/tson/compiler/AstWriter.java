package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;

/**
 * Writes a parsed {@code ast} value back as the source it was parsed from -- the return leg
 * {@link TsonDataParser} never had.
 *
 * <p>The third of this package's three writers, and the only one that writes <b>syntax</b>. {@link
 * TsonObjectWriter} writes a bound Java object and {@link TsonTreeWriter} a {@code TsonValue} tree, both of
 * which describe a <em>value</em> and are free to choose how to spell it; an AST records what an author
 * wrote, including the choices a value no longer remembers -- which token was quoted, what a record's field
 * order was -- so this puts them back rather than deciding them again.
 *
 * <p><b>Why anything needs it.</b> A held template body is an AST: §5.10's open form is the application the
 * author wrote, kept unread until materialisation substitutes its parameters away. Bound like an ordinary
 * object it writes as a description of the AST -- {@code !recordvalue { fields: [ ... ] } } -- which is a
 * faithful rendering of the wrong thing. Written as syntax it is {@code !choice { variants: [ T error ] }},
 * which is what the author wrote and what reads back.
 *
 * <p>Package visible: the AST is {@code tson-compiler}'s own model of source, and writing one is this
 * package's business rather than a service offered to consumers.
 */
final class AstWriter {

	private AstWriter() {
	}

	/** {@code value} as source, annotations and type-ref first, in §7.4's order. */
	static void write(DataValue value, TsonDataEmitter emitter) {
		for (Annotation annotation : value.annotations()) {
			annotation.value().ifPresentOrElse(
					carried -> {
						emitter.beginAnnotation(annotation.name());
						write(carried, emitter);
						emitter.endAnnotation();
					},
					() -> emitter.annotation(annotation.name()));
		}
		value.typeRef().ifPresent(emitter::typeRef);
		write(value.coreValue(), emitter);
	}

	private static void write(CoreValue value, TsonDataEmitter emitter) {
		switch (value) {
			// The form is put back as it was found. A quoted token and an unquoted one denote the same value
			// under a schema ([TSON-DATA] §4.4), so a writer that chose for itself would be within its rights
			// about the value and wrong about the source.
			case TokenValue token -> {
				if (token.form() == TokenForm.UNQUOTED) {
					emitter.unquotedToken(token.text());
				} else {
					emitter.quotedString(token.text());
				}
			}
			case RecordValue record -> {
				emitter.beginRecord();
				for (RecordValue.Field field : record.fields()) {
					emitter.field(field.name());
					write(field.value(), emitter);
				}
				emitter.endRecord();
			}
			case MapValue map -> {
				emitter.beginMap();
				for (MapValue.MapEntry entry : map.entries()) {
					emitter.beforeMapEntry();
					write(entry.key(), emitter);
					emitter.mapArrow();
					write(entry.value(), emitter);
				}
				emitter.endMap();
			}
			case ArrayValue array -> {
				emitter.beginArray();
				for (ScopedValue element : array.elements()) {
					emitter.beforeArrayElement();
					write(element, emitter);
				}
				emitter.endArray();
			}
			case AbsentValue ignored -> emitter.absentValue();
			// `{}` is the empty container of whatever the position's own type is ([TSON-DATA] §2.8), and it is
			// spelled the same way whichever that turns out to be.
			case EmptyBrace ignored -> {
				emitter.beginRecord();
				emitter.endRecord();
			}
		}
	}

	/** A scoped value keeps its {@code !!schema} reference, which is part of the source too. */
	private static void write(ScopedValue value, TsonDataEmitter emitter) {
		value.schemaRef().ifPresent(emitter::schemaRef);
		write(value.value(), emitter);
	}
}

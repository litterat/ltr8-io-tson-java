package io.ltr8.tson.compiler.config;

import io.ltr8.annotation.DataBridge;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.ApplicationRef;
import io.ltr8.tson.schema.meta.NameRef;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * {@link TypeRef} as an atom host: its data form is the reference's text, parsed by {@link ReferenceGrammar} and
 * written as {@link TypeRef#text} ([TSON-DATA] §7.4) -- the text a reader rolls a
 * reference's tree into in TSON, where it is written unquoted, and what a JSON string holds. A reference-typed slot
 * of a schema read does not come through here ({@code BindTargets} sets {@code TypeNameSlotReader} in place, which
 * records the name for the linker), and the writers write a reference themselves; this serves a reference bound
 * from its text anywhere else, a JSON string's among them.
 *
 * <p><b>Each variant is an atom as well</b> ({@link ForName}, {@link ForApplication}): a value written where no
 * component declares its type -- an annotation's value, a value entry's -- is described by its own class, which is
 * the variant, never the interface.
 */
public final class TypeRefTextBridge implements DataBridge<String, TypeRef> {

    /** The bridge; it holds nothing. */
    public TypeRefTextBridge() {
    }

    @Override
    public String toData(TypeRef reference) {
        return reference.text();
    }

    @Override
    public TypeRef toObject(String text) {
        return ReferenceGrammar.parse(text);
    }

    /** {@link NameRef} as an atom host: the reference's text, which must spell a name. */
    public static final class ForName implements DataBridge<String, NameRef> {

        /** The bridge; it holds nothing. */
        public ForName() {
        }

        @Override
        public String toData(NameRef reference) {
            return reference.text();
        }

        @Override
        public NameRef toObject(String text) {
            if (ReferenceGrammar.parse(text) instanceof NameRef name) {
                return name;
            }
            throw new AtomParseException("'" + text + "' is an application, not a name", "a name");
        }
    }

    /** {@link ApplicationRef} as an atom host: the reference's text, which must spell an application. */
    public static final class ForApplication implements DataBridge<String, ApplicationRef> {

        /** The bridge; it holds nothing. */
        public ForApplication() {
        }

        @Override
        public String toData(ApplicationRef reference) {
            return reference.text();
        }

        @Override
        public ApplicationRef toObject(String text) {
            if (ReferenceGrammar.parse(text) instanceof ApplicationRef application) {
                return application;
            }
            throw new AtomParseException("'" + text + "' is a name, not an application", "an application");
        }
    }
}

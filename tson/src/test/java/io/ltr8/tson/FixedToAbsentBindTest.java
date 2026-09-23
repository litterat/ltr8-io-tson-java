package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A field pinned to absence ({@code type? = _}) bound to a component of its own. The pin carries no value, so
 * bind mode has nothing to decode for it ahead of a read: the component receives {@code null} whether the
 * document omits the field or writes {@code _}, and a written value is the pin contradicted.
 */
class FixedToAbsentBindTest {

    private static final String ID = "https://example.test/retired.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/retired.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              person => { name: text  retired: text? = _ }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    public record Person(String name, String retired) {
    }

    private static Tson bound() {
        SchemaSource source = uri -> SCHEMA;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("person", Person.class)).orElse(SchemaMetaNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
        tson.resolve(SCHEMA);
        return tson;
    }

    private static Person read(String body) {
        return bound().objectReader().read("!!schema:\"" + ID + "\"\n!person " + body, Person.class);
    }

    @Test
    void omittedOrWrittenAbsentTheComponentIsNull() {
        assertEquals(new Person("Ada", null), read("{ name: Ada }"));
        assertEquals(new Person("Ada", null), read("{ name: Ada  retired: _ }"));
    }

    @Test
    void aWrittenValueContradictsThePin() {
        assertThrows(ReadException.class, () -> read("{ name: Ada  retired: yes }"));
    }
}

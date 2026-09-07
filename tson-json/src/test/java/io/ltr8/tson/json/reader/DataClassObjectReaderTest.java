package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.json.JsonParseException;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonStream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's own contract, which is the one thing {@code JsonObjectReaderTest} cannot state: it binds a
 * value and stops.
 *
 * <p>What it binds is tested through the facade, where a reader is actually used. What is here is the
 * property the split exists for, and the one the schema-directed decode will rely on when it becomes a
 * second engine under the same door.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DataClassObjectReaderTest {

    public record Person(String name, int age) {
    }

    private static final DataClassObjectReader ENGINE =
            new DataClassObjectReader(DataBindContext.builder().build(), false);

    @Test
    void it_binds_one_value_and_leaves_the_source_where_that_value_ended() {
        // Positioned mid-document: the engine takes the object and nothing after it, so the caller's own
        // framing still sees the array's remaining events.
        JsonStream events = new JsonStream("[{\"name\": \"Ada\", \"age\": 36}, 99]");
        assertInstanceOf(JsonEvent.ArrayStart.class, events.next());

        assertEquals(new Person("Ada", 36), ENGINE.read(events, Person.class));

        assertEquals("99", ((JsonEvent.NumberValue) events.next()).literal());
        assertInstanceOf(JsonEvent.ArrayEnd.class, events.next());
        assertInstanceOf(JsonEvent.EndOfDocument.class, events.next());
    }

    @Test
    void it_returns_before_the_drain_and_the_stream_is_what_refuses_what_follows() {
        // The engine binds the root value and returns without complaint -- trailing content is not its
        // business. The refusal is JsonStream's, raised the moment anything pulls past the root value, and
        // the facade's contribution is only to make that pull happen. So the engine composes and the
        // document still cannot end early.
        JsonStream events = new JsonStream("{\"name\": \"Ada\", \"age\": 36} 99");
        assertEquals(new Person("Ada", 36), ENGINE.read(events, Person.class));
        assertTrue(assertThrows(JsonParseException.class, events::next)
                .getMessage().contains("this one is complete"));
    }

    @Test
    void a_failure_inside_the_value_still_reaches_the_caller() {
        JsonStream events = new JsonStream("{\"name\": ]}");
        assertThrows(JsonParseException.class, () -> ENGINE.read(events, Person.class));
    }
}

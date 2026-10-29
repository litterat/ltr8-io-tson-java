package io.ltr8.tson.compiler;

import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaStripperTest {

    private static final String PIN = "?sha256=" + "0123456789abcdef".repeat(4);

    @Test
    void dropsTheIdPinsAndProseAndShortensTheSpecLibrary() {
        String schema = """
                !!id:"https://example.test/thing-1.tn%s"
                !!import:"https://tson.io/2026/39/m/meta.tn%s"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !!import:"https://example.test/shapes-1.tn%s"
                @doc:\"""
                  Things.
                  \"""
                !schema {
                  @doc:"An identifier."
                  thing => int32

                  @title:"Point"
                  @comment:"Kept in step with shapes-1."
                  @deprecated
                  point => {
                    @doc:\"""
                      Across.
                      \"""
                    @examples:[1.0 2.5]
                    x: float64
                    y?: float64 ~ 0.0
                  }
                }
                """.formatted(PIN, PIN, PIN);

        assertEquals("""
                !!import:"39/meta"
                !!import:"39/core"
                !!import:"https://example.test/shapes-1.tn"
                !schema {
                thing => int32
                @deprecated point => { x: float64 y?: float64 ~ 0.0 }
                }
                """, TsonSchemaStripper.strip(schema));
    }

    /** Keeping the documentation keeps what a reader is shown, and still drops the maintainers' notes. */
    @Test
    void keepingDocsDropsOnlyTheComments() {
        String schema = """
                !!import:"https://tson.io/2026/39/m/meta.tn"
                @doc:"Shapes."
                !schema {
                  @doc:"A point." @title:"Point" @comment:"Kept in step with shapes-1."
                  point => { @examples:[1.0 2.5] x: float64 }
                }
                """;

        assertEquals("""
                !!import:"39/meta"
                @doc:"Shapes." !schema {
                @doc:"A point." @title:"Point" point => { @examples:[1.0 2.5] x: float64 }
                }
                """, TsonSchemaStripper.stripKeepingDocs(schema));
    }

    /** Tokens that touched still touch, and a run of whitespace becomes one space wherever it was. */
    @Test
    void keepsAdjacencyAsWritten() {
        String schema = """
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {   pair    =>   {   a?:text~"x"     b:  array<int32>  }   }
                """;

        assertEquals("!!import:\"39/meta\"\n!schema {\npair => { a?:text~\"x\" b: array<int32> }\n}\n",
                TsonSchemaStripper.strip(schema));
    }

    /** A multi-line token that stays is rewritten single-line as the same text, escapes and all. */
    @Test
    void rewritesAMultiLineTokenSingleLine() {
        String schema = """
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  @title:\"""
                    Two "quoted"
                    lines
                    \"""
                  thing => int32
                }
                """;

        assertEquals("!!import:\"39/meta\"\n!schema {\n@title:\"Two \\\"quoted\\\"\\nlines\" thing => int32\n}\n",
                TsonSchemaStripper.stripKeepingDocs(schema));
    }

    /** Any revision of the spec's library is shortened; a look-alike outside it keeps its URL, unpinned. */
    @Test
    void shortensOnlyTheSpecLibrary() {
        String schema = """
                !!import:"http://tson.io/2026/39/m/meta.tn%s"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/x/core.tn"
                !!import:"https://example.test/2026/39/m/core.tn"
                !schema { thing => int32 }
                """.formatted(PIN);

        assertEquals("""
                !!import:"39/meta"
                !!import:"39/meta-kernel"
                !!import:"https://tson.io/2026/39/x/core.tn"
                !!import:"https://example.test/2026/39/m/core.tn"
                !schema {
                thing => int32
                }
                """, TsonSchemaStripper.strip(schema));
    }

    @Test
    void stripsEveryBundledSchemaToValidSyntax() {
        for (String id : List.of(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID)) {
            String source = TsonBundledSchemas.fetch(id);
            String stripped = TsonSchemaStripper.strip(source);

            assertTrue(stripped.length() < source.length() / 2, id);
            assertFalse(stripped.contains("@doc"), id);
            assertFalse(stripped.contains("@comment"), id);
            assertFalse(stripped.contains("@title"), id);
            assertFalse(stripped.contains("@examples"), id);
            assertFalse(stripped.contains("sha256"), id);
            assertTrue(stripped.lines().allMatch(line -> line.equals("}") || !line.isBlank()), id);
        }
    }

    /** A declaration's line starts at its first annotation, or at its name when a removed {@code @doc} led it. */
    @Test
    void startsEachDeclarationOnItsOwnLine() {
        String schema = """
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema { @doc:"Paired." @title:"Pair" pair => <A, B> { first: A second: B } @deprecated old => int32
                  @doc:"Last." last => { @examples:{ a => 1 } m?: int32 } }
                """;

        assertEquals("""
                !!import:"39/meta"
                !schema {
                pair => <A, B> { first: A second: B }
                @deprecated old => int32
                last => { m?: int32 }
                }
                """, TsonSchemaStripper.strip(schema));
    }

    @Test
    void refusesADataDocument() {
        assertThrows(ParseException.class, () -> TsonSchemaStripper.strip("{ a: 1 }\n"));
    }

    @Test
    void refusesAMalformedSchema() {
        assertThrows(ParseException.class, () -> TsonSchemaStripper.strip("""
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema { thing => }
                """));
    }
}

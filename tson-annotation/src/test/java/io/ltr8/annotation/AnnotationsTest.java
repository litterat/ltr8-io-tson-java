/*
 * Copyright (c) 2026, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ltr8.annotation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query surface, sized to what annotations are actually used for. Across the spec's own bundled
 * schemas the whole vocabulary is markers ({@code @numeric}, {@code @disjoint}, {@code @annotation}) and
 * single scalars ({@code @doc} text, {@code @bounded} boolean, {@code @ordered} enum) -- so
 * {@link Annotations#has} and {@link Annotations#value} carry nearly all the traffic, and repeats, though
 * §3.1 permits them, barely appear.
 */
class AnnotationsTest {

    private static final Annotations DOCUMENTED = Annotations.of(List.of(
            Annotation.of("doc", "a widget"),
            Annotation.of("numeric"),
            Annotation.of("bounded", Boolean.TRUE),
            Annotation.of("tag", "one"),
            Annotation.of("tag", "two")));

    @Test
    void aMarkerIsTestedByPresence() {
        assertTrue(DOCUMENTED.has("numeric"));
        assertFalse(DOCUMENTED.has("nosuch"));
    }

    @Test
    void aTypedValueComesBackAsItsOwnJavaType() {
        assertEquals("a widget", DOCUMENTED.value("doc", String.class).orElseThrow());
        assertEquals(Boolean.TRUE, DOCUMENTED.value("bounded", Boolean.class).orElseThrow());
    }

    @Test
    void anAbsentAnnotationIsEmptyNotAnError() {
        assertTrue(DOCUMENTED.value("nosuch", String.class).isEmpty());
    }

    /**
     * A marker has no value, so a typed lookup for one is empty rather than an error -- "no value of that
     * type here" is true either way, and {@link Annotations#has} is the query that distinguishes them.
     */
    @Test
    void aValuelessAnnotationHasNoTypedValue() {
        assertTrue(DOCUMENTED.value("numeric", String.class).isEmpty());
        assertTrue(DOCUMENTED.has("numeric"));
    }

    @Test
    void repeatsComeBackInSourceOrder() {
        assertEquals(List.of("one", "two"), DOCUMENTED.values("tag", String.class));
    }

    /**
     * The mismatch contract, and the reason it is a throw. The usual cause is not a bad witness but a
     * difference of read mode: with no governing schema there is no declared type to bind a value through,
     * so it is kept structurally and every typed lookup against it would otherwise read as absent -- turning
     * a whole-document mode surprise into silence.
     */
    @Test
    void aPresentValueOfTheWrongTypeThrowsRatherThanReadingAsAbsent() {
        Annotations structural = Annotations.of(List.of(Annotation.of("doc", List.of("not", "a", "string"))));

        ClassCastException thrown =
                assertThrows(ClassCastException.class, () -> structural.value("doc", String.class));

        assertTrue(thrown.getMessage().contains("'@doc'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("java.lang.String"), thrown.getMessage());
    }

    @Test
    void theRawOrderedListIsStillReachable() {
        assertEquals(5, DOCUMENTED.values().size());
        assertEquals("doc", DOCUMENTED.values().get(0).name());
        assertEquals(2, DOCUMENTED.getAll("tag").size());
    }

    @Test
    void emptyIsEmptyAndEqual() {
        assertTrue(Annotations.empty().isEmpty());
        assertEquals(Annotations.empty(), Annotations.of(List.of()));
    }

    /** Structural equality, which is what keeps a bound object's own {@code equals} usable. */
    @Test
    void equalContentsAreEqual() {
        assertEquals(Annotations.of(List.of(Annotation.of("doc", "x"))),
                Annotations.of(List.of(Annotation.of("doc", "x"))));
    }
}

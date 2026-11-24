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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedMapTest {

    private static final Annotations DOCUMENTED = Annotations.of(List.of(Annotation.of("doc", "a widget")));

    @Test
    void anImmutableMapIsItsOwnCopy() {
        AnnotatedMap<String, Integer> map = AnnotatedMap.copyOf(Map.of("a", 1));
        assertSame(map, AnnotatedMap.copyOf(map));
    }

    @Test
    void aMutableMapIsCopiedWithItsKeyAnnotations() {
        AnnotatedMap<String, Integer> filling = new AnnotatedMap<>();
        filling.put("a", 1, DOCUMENTED);
        AnnotatedMap<String, Integer> copy = AnnotatedMap.copyOf(filling);
        filling.put("b", 2);

        assertNotSame(filling, copy);
        assertEquals(Map.of("a", 1), copy);
        assertEquals(DOCUMENTED, copy.getAnnotations("a"));
        assertThrows(UnsupportedOperationException.class, () -> copy.put("c", 3));
    }

    @Test
    void aCopyOfAPlainMapDoesNotSeeLaterChangesToIt() {
        Map<String, Integer> source = new LinkedHashMap<>(Map.of("a", 1));
        AnnotatedMap<String, Integer> copy = AnnotatedMap.copyOf(source);
        source.put("b", 2);
        assertEquals(Map.of("a", 1), copy);
    }

    @Test
    void replacingAKeysAnnotationsLeavesTheOriginalUntouched() {
        AnnotatedMap<String, Integer> plain = AnnotatedMap.copyOf(Map.of("a", 1));
        AnnotatedMap<String, Integer> documented = plain.withAnnotations("a", DOCUMENTED);

        assertTrue(plain.getAnnotations("a").isEmpty());
        assertEquals(DOCUMENTED, documented.getAnnotations("a"));
        assertTrue(documented.withAnnotations("a", Annotations.empty()).getAnnotations("a").isEmpty());
        assertEquals(DOCUMENTED, documented.getAnnotations("a"));
    }
}

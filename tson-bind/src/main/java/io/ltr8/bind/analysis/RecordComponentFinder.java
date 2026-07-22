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
package io.ltr8.bind.analysis;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Union;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Finds tuple components for a genuine Java record via {@link Class#getRecordComponents()} --
 * the JDK's own authoritative record introspection API (stable since Java 16), not a heuristic.
 * Each record component maps directly to one canonical-constructor argument, by position, and to
 * its own accessor method. Records have no setters; they're immutable by construction.
 *
 * <p>{@code @Field}/{@code @Union} are read from the accessor method, not
 * {@code RecordComponent} itself: both annotations' {@code @Target} predates record components
 * and doesn't list {@code ElementType.RECORD_COMPONENT}, so per JLS 8.10.3 an annotation written
 * on a record header parameter propagates to the field, the constructor parameter, and (for a
 * compiler-synthesized accessor) the accessor method -- but not to the {@code RecordComponent}
 * object itself, so {@code component.getAnnotation(...)} would always return {@code null} here.
 */
public class RecordComponentFinder implements ComponentFinder {

    @Override
    public void findComponents(Class<?> clss, Constructor<?> constructor, List<ComponentInfo> fields)
            throws CodeAnalysisException {

        RecordComponent[] components = clss.getRecordComponents();
        if (components == null) {
            throw new CodeAnalysisException(String.format("Class '%s' is not a record", clss.getName()));
        }

        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        for (int x = 0; x < components.length; x++) {
            RecordComponent component = components[x];
            Method accessor = component.getAccessor();

            ComponentInfo info = new ComponentInfo(component.getName(), component.getType());
            info.setConstructorArgument(x);

            try {
                info.setReadMethod(lookup.unreflect(accessor));
            } catch (IllegalAccessException e) {
                throw new CodeAnalysisException(String.format(
                        "Failed to access accessor for record component '%s' on '%s'", component.getName(),
                        clss.getName()), e);
            }

            Type genericType = component.getGenericType();
            if (genericType instanceof ParameterizedType parameterizedType) {
                info.setParamType(parameterizedType);
            }

            Field fieldAnnotation = accessor.getAnnotation(Field.class);
            if (fieldAnnotation != null) {
                info.setField(fieldAnnotation);
            }

            Union unionAnnotation = accessor.getAnnotation(Union.class);
            if (unionAnnotation != null) {
                info.setUnion(unionAnnotation);
            }

            fields.add(info);
        }
    }
}

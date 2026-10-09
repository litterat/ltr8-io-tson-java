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
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
 * {@code RecordComponent} itself: none of the three annotations' {@code @Target} lists {@code
 * ElementType.RECORD_COMPONENT}, so per JLS 8.10.3 an annotation written on a record header
 * parameter propagates to the field, the constructor parameter, and (for a compiler-synthesized
 * accessor) the accessor method -- but not to the {@code RecordComponent} object itself, so {@code
 * component.getAnnotation(...)} would always return {@code null} here.
 */
public class RecordComponentFinder implements ComponentFinder {

    @Override
    public void findComponents(Class<?> clss, Constructor<?> constructor, List<ComponentInfo> fields)
            throws CodeAnalysisException {

        RecordComponent[] components = clss.getRecordComponents();
        if (components == null) {
            throw new CodeAnalysisException(String.format("Class '%s' is not a record", clss.getName()));
        }

        if (!isCanonical(constructor, components)) {
            // A profile selected a constructor other than the canonical one, so the record's own component
            // list is no longer the shape being bound -- it describes the whole class, where this constructor
            // takes a subset. The parameters decide, and each one is matched back to the component of that
            // name for its accessor and type. See findProfiledComponents.
            findProfiledComponents(clss, constructor, components, fields);
            return;
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

            // §The class's own, not the wire's. Read from the accessor and the component alike: the marker
            // targets both, and which one carries it depends on where the author wrote it.
            info.setUnbound(accessor.isAnnotationPresent(Unbound.class)
                    || component.isAnnotationPresent(Unbound.class));

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

    /**
     * Whether {@code constructor} is the record's canonical one -- its parameters being the components, in
     * order. Compared by type rather than by name, since a secondary constructor's parameter names are
     * {@code arg0}/{@code arg1} unless the class was compiled with {@code -parameters}.
     *
     * <p>A secondary constructor that happens to take the same types in the same order is indistinguishable
     * from the canonical one here, and binding through either produces the same values, so the ambiguity
     * costs nothing.
     */
    private static boolean isCanonical(Constructor<?> constructor, RecordComponent[] components) {
        if (constructor.getParameterCount() != components.length) {
            return false;
        }
        Class<?>[] parameters = constructor.getParameterTypes();
        for (int x = 0; x < components.length; x++) {
            if (!parameters[x].equals(components[x].getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The components of a record bound through a constructor that takes a subset of them -- one
     * {@link ComponentInfo} per parameter, in parameter order, each carrying the accessor of the record
     * component it names.
     *
     * <p><b>Where the names come from</b>, in order: {@code @Profile(fields = ...)} on the constructor, then
     * {@link Field} on the parameter, then the parameter's own reflected name. The last works only where the
     * class was compiled with {@code -parameters} -- a secondary constructor's names are otherwise
     * {@code arg0} -- which is why the first two exist. A name that matches no component is an error rather
     * than a component invented from the parameter, since the accessor has to come from somewhere.
     *
     * <p>Components the constructor omits are simply not part of this profile's shape. That is the feature:
     * a class serving an older schema version binds the fields that version had, and fills the rest itself.
     */
    private static void findProfiledComponents(Class<?> clss, Constructor<?> constructor,
            RecordComponent[] components, List<ComponentInfo> fields) throws CodeAnalysisException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Profile profile = constructor.getAnnotation(Profile.class);
        String[] declared = profile == null ? new String[0] : profile.fields();
        Parameter[] parameters = constructor.getParameters();
        if (declared.length != 0 && declared.length != parameters.length) {
            throw new CodeAnalysisException(String.format(
                    "@Profile on '%s' lists %d field name(s) for a constructor taking %d parameter(s)",
                    clss.getName(), declared.length, parameters.length));
        }

        for (int x = 0; x < parameters.length; x++) {
            String name = nameOf(clss, parameters[x], declared, x);
            RecordComponent component = componentNamed(clss, components, name);
            ComponentInfo info = new ComponentInfo(name, component.getType());
            info.setConstructorArgument(x);
            try {
                info.setReadMethod(lookup.unreflect(component.getAccessor()));
            } catch (IllegalAccessException e) {
                throw new CodeAnalysisException(String.format(
                        "Failed to access accessor for record component '%s' on '%s'", name, clss.getName()), e);
            }
            if (component.getGenericType() instanceof ParameterizedType parameterizedType) {
                info.setParamType(parameterizedType);
            }
            Field renamed = parameters[x].getAnnotation(Field.class);
            if (renamed != null) {
                info.setField(renamed);
            }
            fields.add(info);
        }
    }

    private static String nameOf(Class<?> clss, Parameter parameter, String[] declared, int index)
            throws CodeAnalysisException {
        if (declared.length != 0) {
            return declared[index];
        }
        Field renamed = parameter.getAnnotation(Field.class);
        if (renamed != null && !renamed.value().isEmpty()) {
            return renamed.value();
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        throw new CodeAnalysisException(String.format(
                "Cannot name parameter %d of the selected constructor on '%s': a secondary constructor keeps no "
                        + "parameter names unless the class was compiled with -parameters. List them with "
                        + "@Profile(fields = {...}) or name this one with @Field", index, clss.getName()));
    }

    private static RecordComponent componentNamed(Class<?> clss, RecordComponent[] components, String name)
            throws CodeAnalysisException {
        for (RecordComponent component : components) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new CodeAnalysisException(String.format(
                "'%s' names no component '%s', so the selected constructor's parameter has no accessor to read "
                        + "it back through", clss.getName(), name));
    }
}

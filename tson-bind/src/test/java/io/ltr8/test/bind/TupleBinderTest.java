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
package io.ltr8.test.bind;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassTuple;

public class TupleBinderTest {

	@Tuple
	public record Point(int x, int y) {
	}

	@Tuple
	public record NameAndAge(String name, int age) {
	}

	public record NotATuple(int x, int y) {
	}

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void resolvesElementsInConstructorOrder() throws Throwable {
		DataClassTuple tupleClass = (DataClassTuple) context.getDescriptor(NameAndAge.class);

		DataClassElement[] elements = tupleClass.elements();
		Assertions.assertEquals(2, elements.length);
		Assertions.assertEquals(String.class, elements[0].dataClass().typeClass());
		Assertions.assertEquals(0, elements[0].index());
		Assertions.assertEquals(int.class, elements[1].dataClass().typeClass());
		Assertions.assertEquals(1, elements[1].index());
	}

	@Test
	public void constructFromPositionalValues() throws Throwable {
		DataClassTuple tupleClass = (DataClassTuple) context.getDescriptor(Point.class);

		Point p = (Point) tupleClass.constructor().invoke(new Object[] { 3, 4 });
		Assertions.assertEquals(new Point(3, 4), p);
	}

	@Test
	public void accessorsReadBackConstructedValues() throws Throwable {
		DataClassTuple tupleClass = (DataClassTuple) context.getDescriptor(Point.class);
		DataClassElement[] elements = tupleClass.elements();

		Object tuple = tupleClass.constructor().invoke(new Object[] { 3, 4 });

		Assertions.assertEquals(3, (int) elements[0].accessor().invoke(tuple));
		Assertions.assertEquals(4, (int) elements[1].accessor().invoke(tuple));
	}

	@Test
	public void constructorRejectsWrongArity() throws Throwable {
		DataClassTuple tupleClass = (DataClassTuple) context.getDescriptor(Point.class);

		Assertions.assertThrows(IllegalArgumentException.class,
				() -> tupleClass.constructor().invoke(new Object[] { 3 }));
	}

	@Tuple
	public static class TupleOnAPlainClass {
	}

	@Test
	public void tupleAnnotationOnANonRecordClassFails() {
		// @Tuple's own @Target is ElementType.TYPE, so it compiles on a plain class too --
		// DefaultTupleBinder guards against that misuse explicitly rather than doing something
		// odd, since only a genuine Java record has record components to resolve positionally.
		Assertions.assertThrows(DataBindException.class, () -> context.getDescriptor(TupleOnAPlainClass.class));
	}

	@Test
	public void plainJavaRecordWithoutAnnotationBindsAsAnOrdinaryRecordNotATuple() throws Throwable {
		// NotATuple has no @Tuple -- it should resolve via the ordinary named-field record path,
		// not DataClassTuple, confirming the annotation (not "is it a record") is what selects
		// positional binding.
		Object descriptor = context.getDescriptor(NotATuple.class);
		Assertions.assertFalse(descriptor instanceof DataClassTuple);
	}
}

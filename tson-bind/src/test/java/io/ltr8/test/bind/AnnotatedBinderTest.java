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

import io.ltr8.annotation.Annotated;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;

public class AnnotatedBinderTest {

	// Deliberately not a resolvable atom/record/etc. -- tson-bind has no dependency on
	// tson-mapper's real TsonAnnotations carrier type, and DefaultRecordBinder never calls
	// context.getDescriptor() for an @Annotated component in the first place, so there's nothing
	// for this class to need to resolve to.
	public static class OpaqueCarrier {
	}

	public record Item(@Annotated OpaqueCarrier meta, String name) {
	}

	public record TwoCarriers(@Annotated OpaqueCarrier a, @Annotated OpaqueCarrier b, String name) {
	}

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void annotatedComponentIsFlaggedAndSkipsDescriptorResolution() throws Throwable {
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(Item.class);

		DataClassField[] fields = descriptor.fields();
		Assertions.assertEquals(2, fields.length);

		DataClassField meta = fields[0];
		Assertions.assertEquals("meta", meta.name());
		Assertions.assertTrue(meta.isAnnotationsCarrier());
		Assertions.assertNull(meta.dataClass());

		DataClassField name = fields[1];
		Assertions.assertFalse(name.isAnnotationsCarrier());
	}

	@Test
	public void atMostOneAnnotatedComponentAllowed() {
		Assertions.assertThrows(DataBindException.class, () -> context.getDescriptor(TwoCarriers.class));
	}
}

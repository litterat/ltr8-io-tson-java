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
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;

public class AnnotatedBinderTest {

	// Declaring a component of type Annotations is the whole opt-in -- no marker, and nothing to
	// register. Annotations is not a resolvable atom/record/etc., which is fine: DefaultRecordBinder
	// never calls context.getDescriptor() for a carrier.
	public record Item(Annotations meta, String name) {
	}

	public record TwoCarriers(Annotations a, Annotations b, String name) {
	}

	public record NoCarrier(String name) {
	}

	/** A boxed position: the annotations ride with the value, since a String has nowhere to hold them. */
	public record Boxed(Annotated<String> name, int count) {
	}

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void theCarrierIsInferredFromItsDeclaredTypeAndSkipsDescriptorResolution() throws Throwable {
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(Item.class);

		DataClassField[] fields = descriptor.fields();
		Assertions.assertEquals(2, fields.length);

		// The record names its own carrier, so a reader asks once rather than testing every field.
		DataClassField meta = descriptor.annotationsCarrier().orElseThrow();
		Assertions.assertSame(fields[0], meta);
		Assertions.assertEquals("meta", meta.name());

		// Never bound from an authored value, so no descriptor was resolved for it -- which is what
		// lets the carrier type be one tson-bind knows nothing about beyond its identity.
		Assertions.assertNull(meta.dataClass());
	}

	@Test
	public void aRecordWithoutACarrierHasNone() throws Throwable {
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(NoCarrier.class);

		Assertions.assertTrue(descriptor.annotationsCarrier().isEmpty());
	}

	@Test
	public void aBoxedFieldResolvesToItsValuesDescriptor() throws Throwable {
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(Boxed.class);

		DataClassField name = descriptor.fields()[0];
		DataClassAnnotated boxed = Assertions.assertInstanceOf(DataClassAnnotated.class, name.dataClass());
		Assertions.assertEquals(String.class, boxed.valueClass().typeClass());

		// A box is not a carrier: it annotates one position, not the whole record.
		Assertions.assertTrue(descriptor.annotationsCarrier().isEmpty());
	}

	/**
	 * Metadata does not change what a value is, so two boxes holding the same value are equal however they
	 * are annotated. The map here exercises the hash contract alongside equals -- not a recommendation to key
	 * a map by a box, which {@code AnnotatedMap} exists to avoid.
	 */
	@Test
	public void aBoxProxiesEqualityAndHashToItsValue() {
		Annotations note = Annotations.of(java.util.List.of(Annotation.of("doc", "x")));

		Assertions.assertEquals(Annotated.of("a"), Annotated.of("a", note));
		Assertions.assertEquals(Annotated.of("a").hashCode(), Annotated.of("a", note).hashCode());

		java.util.Map<Annotated<String>, Integer> byName = java.util.Map.of(Annotated.of("a", note), 1);
		Assertions.assertEquals(1, byName.get(Annotated.of("a")));
	}

	@Test
	public void atMostOneCarrierIsAllowed() {
		Assertions.assertThrows(DataBindException.class, () -> context.getDescriptor(TwoCarriers.class));
	}
}

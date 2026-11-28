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

import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Transparent;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A {@code @Transparent} class's descriptor says so; every other descriptor, a bridged one included, does not. */
class TransparentFlagTest {

	private static final DataBindContext CONTEXT = DataBindContext.builder().build();

	public record Point(int x, int y) {
	}

	public sealed interface Held permits HeldPoint, HeldText {
	}

	@Transparent
	public record HeldPoint(Point point) implements Held {
	}

	@Transparent
	public record HeldText(String text) implements Held {
	}

	public static final class BridgedPoint implements ToData<Point> {
		private final Point point;

		public BridgedPoint(Point point) {
			this.point = point;
		}

		@Override
		public Point toData() {
			return point;
		}
	}

	public enum Colour {
		RED
	}

	@Test
	void aTransparentRecordOverARecord() throws DataBindException {
		DataClass descriptor = CONTEXT.getDescriptor(HeldPoint.class);
		assertInstanceOf(DataClassRecord.class, descriptor);
		assertTrue(descriptor.isTransparent());
	}

	@Test
	void aTransparentRecordOverAnAtom() throws DataBindException {
		DataClass descriptor = CONTEXT.getDescriptor(HeldText.class);
		assertInstanceOf(DataClassAtom.class, descriptor);
		assertTrue(descriptor.isTransparent());
	}

	@Test
	void aTransparentUnionMember() throws DataBindException {
		DataClassUnion union = (DataClassUnion) CONTEXT.getDescriptor(Held.class);
		assertFalse(union.isTransparent());
		assertArrayEquals(new Class<?>[] { HeldPoint.class, HeldText.class }, union.memberTypes());
		for (Class<?> member : union.memberTypes()) {
			assertTrue(CONTEXT.getDescriptor(member).isTransparent(), member.getSimpleName());
		}
	}

	@Test
	void aBridgedClassIsNotTransparent() throws DataBindException {
		DataClass toData = CONTEXT.getDescriptor(BridgedPoint.class);
		assertInstanceOf(DataClassRecord.class, toData);
		assertTrue(toData.bridge().isPresent());
		assertFalse(toData.isTransparent());
		DataClass enumeration = CONTEXT.getDescriptor(Colour.class);
		assertTrue(enumeration.bridge().isPresent());
		assertFalse(enumeration.isTransparent());
	}

	@Test
	void aPlainRecordAndItsComponentAreNotTransparent() throws DataBindException {
		assertFalse(CONTEXT.getDescriptor(Point.class).isTransparent());
		assertFalse(CONTEXT.getDescriptor(String.class).isTransparent());
	}
}

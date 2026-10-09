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

import io.ltr8.annotation.Transparent;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;

import java.util.List;

/**
 * {@code @Transparent} resolves to the wrapped component's descriptor with a bridge -- <b>not</b> to a
 * descriptor kind of its own. Every assertion here is about that one claim, because it is what keeps the set
 * of {@code DataClass} kinds closed while still letting a wrapper disappear from the wire.
 */
public class TransparentBinderTest {

	public record Point(int x, int y) {
	}

	/** The shape this exists for: a wrapper naming one value, contributing nothing to the wire form. */
	@Transparent
	public record Held(Point application) {
	}

	@Transparent
	public record HeldAtom(String text) {
	}

	@Transparent
	public record TwoComponents(Point first, Point second) {
	}

	@Transparent
	public record HeldList(List<String> items) {
	}

	/** The same shape as {@link Held}, without the marker -- the control for {@link #transparencyIsDeclaredNotInferred()}. */
	public record Opaque(Point application) {
	}

	public enum Colour {
		RED, GREEN
	}

	@Transparent
	public record HeldEnum(Colour colour) {
	}

	@Transparent
	public static class NotARecord {
		public NotARecord(Point p) {
		}
	}

	DataBindContext context;

	@BeforeEach
	public void setUp() {
		context = DataBindContext.builder().build();
	}

	/**
	 * The central claim: the descriptor is an ordinary {@link DataClassRecord} carrying the <em>component's</em>
	 * fields, so every reader and writer that walks a record walks the wrapped value without knowing a wrapper
	 * was ever there. {@code typeClass} stays the wrapper -- that is what a position declares -- while
	 * {@code dataClass} is what goes on the wire.
	 */
	@Test
	public void aTransparentWrapperTakesItsComponentsShape() throws DataBindException {
		DataClass descriptor = context.getDescriptor(Held.class);

		DataClassRecord record = Assertions.assertInstanceOf(DataClassRecord.class, descriptor);
		Assertions.assertEquals(Held.class, record.typeClass());
		Assertions.assertEquals(Point.class, record.dataClass());
		Assertions.assertEquals(List.of("x", "y"),
				List.of(record.fields()).stream().map(DataClassField::name).toList());
	}

	/**
	 * No new kind of descriptor is minted, which is the constraint this design was chosen for: the wrapper is
	 * expressed entirely as a bridge over a kind that already exists, so a future {@code sealed DataClass}
	 * needs no new permit for it.
	 */
	@Test
	public void transparencyIsABridgeAndNotAKindOfItsOwn() throws DataBindException {
		DataClass descriptor = context.getDescriptor(Held.class);

		Assertions.assertTrue(descriptor.bridge().isPresent());
		Assertions.assertEquals(Point.class, descriptor.bridge().get().dataClass());
	}

	/** The bridge's two handles are the wrapper taken apart and put back together, and they round-trip. */
	@Test
	public void theBridgeUnwrapsAndRewraps() throws Throwable {
		DataClass descriptor = context.getDescriptor(Held.class);
		Held held = new Held(new Point(1, 2));

		Object unwrapped = descriptor.bridge().get().toData().invoke(held);
		Object rewrapped = descriptor.bridge().get().toObject().invoke(unwrapped);

		Assertions.assertEquals(new Point(1, 2), unwrapped);
		Assertions.assertEquals(held, rewrapped);
	}

	/** An atom-shaped component works the same way -- {@code DataClassAtom} is the other kind that carries a bridge. */
	@Test
	public void anAtomComponentIsTransparentToo() throws DataBindException {
		DataClass descriptor = context.getDescriptor(HeldAtom.class);

		DataClassAtom atom = Assertions.assertInstanceOf(DataClassAtom.class, descriptor);
		Assertions.assertEquals(HeldAtom.class, atom.typeClass());
		Assertions.assertEquals(String.class, atom.dataClass());
	}

	/**
	 * <b>One component is not the signal.</b> An unmarked single-component record keeps its own shape and its
	 * own field name, because plenty of them mean their field and are wrong to unwrap. Without this, the
	 * marker would be decoration over a shape heuristic rather than the declaration it is.
	 */
	@Test
	public void transparencyIsDeclaredNotInferred() throws DataBindException {
		DataClass descriptor = context.getDescriptor(Opaque.class);

		DataClassRecord record = Assertions.assertInstanceOf(DataClassRecord.class, descriptor);
		Assertions.assertTrue(descriptor.bridge().isEmpty());
		Assertions.assertEquals(Opaque.class, record.dataClass());
		Assertions.assertEquals(List.of("application"),
				List.of(record.fields()).stream().map(DataClassField::name).toList());
	}

	/** Two components is no wire form at all, and saying so beats picking one. */
	@Test
	public void aWrapperWithTwoComponentsIsRefused() {
		DataBindException e = Assertions.assertThrows(DataBindException.class,
				() -> context.getDescriptor(TwoComponents.class));

		Assertions.assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
	}

	/** Only a record has a component to be transparent to. */
	@Test
	public void aNonRecordIsRefused() {
		DataBindException e = Assertions.assertThrows(DataBindException.class,
				() -> context.getDescriptor(NotARecord.class));

		Assertions.assertTrue(e.getMessage().contains("only meaningful on a record"), e.getMessage());
	}

	/**
	 * A component that resolves to a kind with no bridge-taking constructor is refused rather than silently
	 * losing the bridge -- which would leave the wrapper on the wire, the one outcome the marker exists to
	 * prevent.
	 */
	@Test
	public void aComponentThatCannotCarryABridgeIsRefused() {
		DataBindException e = Assertions.assertThrows(DataBindException.class,
				() -> context.getDescriptor(HeldList.class));

		Assertions.assertTrue(e.getMessage().contains("only a record or an atom carries a bridge"),
				e.getMessage());
	}

	/** One position, one bridge: an already-bridged component (an enum) has no room for a second. */
	@Test
	public void anAlreadyBridgedComponentIsRefused() {
		DataBindException e = Assertions.assertThrows(DataBindException.class,
				() -> context.getDescriptor(HeldEnum.class));

		Assertions.assertTrue(e.getMessage().contains("itself bridged"), e.getMessage());
	}
}

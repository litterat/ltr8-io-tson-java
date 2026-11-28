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

import io.ltr8.annotation.DataBridge;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.UUIDBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code Builder.registerAtom(Class, DataBridge)}: the bridge's methods are found as the JVM finds them. */
class AtomBridgeRegistrationTest {

	private static final String TAG = Tag.class.getName();

	private static final String CONVERTS = ": a registered bridge names the classes it converts between, "
			+ "DataBridge<D, O>";

	public record Tag(String text) {
	}

	public record Labelled(Tag tag) {
	}

	public static final class TagBridge implements DataBridge<String, Tag> {
		@Override
		public String toData(Tag tag) {
			return tag.text();
		}

		@Override
		public Tag toObject(String text) {
			return new Tag(text);
		}
	}

	/** Declares {@code toData} and {@code toObject} at its type parameter, so a subclass has them only erased. */
	public abstract static class ParsedText<O> implements DataBridge<String, O> {
		private final Function<O, String> print;
		private final Function<String, O> parse;

		protected ParsedText(Function<O, String> print, Function<String, O> parse) {
			this.print = print;
			this.parse = parse;
		}

		@Override
		public String toData(O object) {
			return print.apply(object);
		}

		@Override
		public O toObject(String text) {
			return parse.apply(text);
		}
	}

	public static final class ParsedTag extends ParsedText<Tag> {
		public ParsedTag() {
			super(Tag::text, Tag::new);
		}
	}

	public abstract static class ParsedTextBelow<O> extends ParsedText<O> {
		protected ParsedTextBelow(Function<O, String> print, Function<String, O> parse) {
			super(print, parse);
		}
	}

	public static final class TwoLevelsParsedTag extends ParsedTextBelow<Tag> {
		public TwoLevelsParsedTag() {
			super(Tag::text, Tag::new);
		}
	}

	abstract static class HiddenParsedText<O> extends ParsedText<O> {
		HiddenParsedText(Function<O, String> print, Function<String, O> parse) {
			super(print, parse);
		}
	}

	/** Public, over a super class that is not: the public lookup reaches its methods through it. */
	public static final class HiddenBaseParsedTag extends HiddenParsedText<Tag> {
		public HiddenBaseParsedTag() {
			super(Tag::text, Tag::new);
		}
	}

	static final class HiddenTagBridge implements DataBridge<String, Tag> {
		@Override
		public String toData(Tag tag) {
			return tag.text();
		}

		@Override
		public Tag toObject(String text) {
			return new Tag(text);
		}
	}

	public static final class OpenBridge<O> extends ParsedText<O> {
		public OpenBridge(Function<O, String> print, Function<String, O> parse) {
			super(print, parse);
		}
	}

	@SuppressWarnings("rawtypes")
	public static final class RawBridge implements DataBridge {
		@Override
		public Object toData(Object object) {
			return ((Tag) object).text();
		}

		@Override
		public Object toObject(Object data) {
			return new Tag((String) data);
		}
	}

	@Test
	void aBridgeDeclaringItsMethodsAtTheAtomsTypeRoundTrips() throws DataBindException {
		assertRoundTrips(new TagBridge());
	}

	@Test
	void aBridgeInheritingItsMethodsFromAGenericSuperClassRoundTrips() throws DataBindException {
		assertRoundTrips(new ParsedTag());
	}

	@Test
	void aBridgeInheritingItsMethodsFromTwoLevelsUpRoundTrips() throws DataBindException {
		assertRoundTrips(new TwoLevelsParsedTag());
	}

	@Test
	void aPublicBridgeOverASuperClassThatIsNotRoundTrips() throws DataBindException {
		assertRoundTrips(new HiddenBaseParsedTag());
	}

	@Test
	void aBridgeThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String bridge = HiddenTagBridge.class.getName();
		assertRefusedAlike(new HiddenTagBridge(),
				bridge + ", the bridge registered for atom " + TAG + ", is a class the public lookup cannot access",
				"symbolic reference class is not accessible: class " + bridge + ", from public Lookup");
	}

	@Test
	void aBridgeConvertingAnotherClassIsRefused() {
		assertRefusedAlike(new UUIDBridge(), UUIDBridge.class.getName() + ", the bridge registered for atom " + TAG
				+ ", converts java.util.UUID, not " + TAG);
	}

	@Test
	void aBridgeLeavingItsObjectTypeOpenIsRefused() {
		assertRefusedAlike(new OpenBridge<>(Tag::text, Tag::new), OpenBridge.class.getName() + ", the bridge "
				+ "registered for atom " + TAG + ", implements DataBridge<java.lang.String, O>, leaving its object "
				+ "type open" + CONVERTS);
	}

	@Test
	void aRawBridgeIsRefused() {
		assertRefusedAlike(new RawBridge(), RawBridge.class.getName() + ", the bridge registered for atom " + TAG
				+ ", reaches DataBridge raw" + CONVERTS);
	}

	private static void assertRoundTrips(DataBridge<?, ?> bridge) throws DataBindException {
		DataBindContext context = DataBindContext.builder().registerAtom(Tag.class, bridge).build();
		MapMapper mapper = new MapMapper(context);
		Labelled labelled = new Labelled(new Tag("a"));

		assertEquals(String.class, ((DataClassAtom) context.getDescriptor(Tag.class)).dataClass());
		assertEquals(Map.of("tag", "a"), mapper.toMap(labelled));
		assertEquals(labelled, mapper.toObject(Labelled.class, mapper.toMap(labelled)));
	}

	/**
	 * Each in two fresh contexts: {@code chain} is the refusal's message and its causes', outermost first, under the
	 * {@code IllegalArgumentException} that {@code build()} throws, and naming nothing by identity they read the same
	 * in each.
	 */
	private static void assertRefusedAlike(DataBridge<?, ?> bridge, String... chain) {
		assertAll(Stream.of(1, 2).map(run -> (Executable) () -> {
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
					() -> DataBindContext.builder().registerAtom(Tag.class, bridge).build());
			List<String> messages = new ArrayList<>();
			for (Throwable at = refused.getCause(); at != null; at = at.getCause()) {
				messages.add(at.getMessage());
			}
			assertEquals(List.of(chain), messages);
		}));
	}
}

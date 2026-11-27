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

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.GenericComponents.CtorArray;
import io.ltr8.test.data.GenericComponents.CtorBare;
import io.ltr8.test.data.GenericComponents.CtorList;
import io.ltr8.test.data.GenericComponents.FieldAccessorBare;
import io.ltr8.test.data.GenericComponents.HoldsAllNamed;
import io.ltr8.test.data.GenericComponents.HoldsBares;
import io.ltr8.test.data.GenericComponents.HoldsExtending;
import io.ltr8.test.data.GenericComponents.HoldsProfBares;
import io.ltr8.test.data.GenericComponents.HoldsProfExtending;
import io.ltr8.test.data.GenericComponents.MergedArray;
import io.ltr8.test.data.GenericComponents.MergedBare;
import io.ltr8.test.data.GenericComponents.MergedList;
import io.ltr8.test.data.GenericComponents.NamedBare;
import io.ltr8.test.data.GenericComponents.NamedList;
import io.ltr8.test.data.GenericComponents.NamedMap;
import io.ltr8.test.data.GenericComponents.NamedOpt;
import io.ltr8.test.data.GenericComponents.PairArray;
import io.ltr8.test.data.GenericComponents.PairBare;
import io.ltr8.test.data.GenericComponents.PairList;
import io.ltr8.test.data.GenericComponents.PlainNamedList;
import io.ltr8.test.data.GenericComponents.ProfArray;
import io.ltr8.test.data.GenericComponents.ProfBare;
import io.ltr8.test.data.GenericComponents.ProfList;
import io.ltr8.test.data.GenericComponents.ProfMap;
import io.ltr8.test.data.GenericComponents.PubArray;
import io.ltr8.test.data.GenericComponents.PubBare;
import io.ltr8.test.data.GenericComponents.PubList;
import io.ltr8.test.data.GenericComponents.RecArray;
import io.ltr8.test.data.GenericComponents.RecBare;
import io.ltr8.test.data.GenericComponents.RecBridged;
import io.ltr8.test.data.GenericComponents.RecList;
import io.ltr8.test.data.GenericComponents.RecMap;
import io.ltr8.test.data.GenericComponents.RecNested;
import io.ltr8.test.data.GenericComponents.RecOpt;
import io.ltr8.test.data.GenericComponents.RecOptList;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A generic class held at an argument carries each component through both mappers at that argument, on every finder
 * path.
 */
class GenericComponentRoundTripTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	@Test
	void aBareVariableAndAnArrayOfOneRoundTripAtTheArgument() {
		PubBare<String> pub = new PubBare<>();
		pub.value = "pub";
		PubArray<String> pubArray = new PubArray<>();
		pubArray.value = new String[] { "pub0", "pub1" };
		PairBare<String> pair = new PairBare<>();
		pair.setValue("pair");
		PairArray<String> pairArray = new PairArray<>();
		pairArray.setValue(new String[] { "pair0" });
		PubBare<List<String>> pubList = new PubBare<>();
		pubList.value = List.of("l0", "l1");
		HoldsBares bares = new HoldsBares(new RecBare<>("rec"), new RecArray<>(new String[] { "rec0", "rec1" }),
				new CtorBare<>("ctor"), new CtorArray<>(new String[] { "ctor0" }), new FieldAccessorBare<>("field"),
				pub, pubArray, pair, pairArray, new MergedBare<>("merged"), new MergedArray<>(new String[] { "m0" }),
				pubList);
		String expected = "rec [rec0, rec1] ctor [ctor0] field pub [pub0, pub1] pair [pair0] merged [m0] [l0, l1]";
		assertAll(() -> assertEquals(expected, values(bares)),
				() -> assertEquals(expected, values((HoldsBares) maps.toObject(HoldsBares.class, maps.toMap(bares)))),
				() -> assertEquals(expected, values(arrays.toObject(HoldsBares.class, arrays.toArray(bares)))));
	}

	@Test
	void aProfileConstructorTakesABareVariableAndAnArrayOfOneAtTheArgument() {
		DataBindContext profiled = DataBindContext.builder().profile("p").build();
		MapMapper maps = new MapMapper(profiled);
		ArrayMapper arrays = new ArrayMapper(profiled);
		HoldsProfBares bares = new HoldsProfBares(new ProfBare<>("one"), new ProfArray<>(new String[] { "two" }));
		assertAll(
				() -> assertEquals("one [two]", values((HoldsProfBares) maps.toObject(HoldsProfBares.class, maps.toMap(bares)))),
				() -> assertEquals("one [two]",
						values(arrays.toObject(HoldsProfBares.class, arrays.toArray(bares)))));
	}

	@Test
	void aParameterOnlyFieldNamesRoundTripsAtItsGenericType() {
		HoldsAllNamed named = new HoldsAllNamed(new NamedList<>(List.of("l0", "l1")), new NamedMap<>(Map.of("k", "v")),
				new NamedBare<>("bare"), new NamedOpt<>(Optional.of("opt")), new PlainNamedList(List.of("p0")));
		String expected = "[l0, l1] {k=v} bare Optional[opt] [p0]";
		assertAll(() -> assertEquals(expected, values(named)),
				() -> assertEquals(expected, values((HoldsAllNamed) maps.toObject(HoldsAllNamed.class,
						maps.toMap(named)))),
				() -> assertEquals(expected, values(arrays.toObject(HoldsAllNamed.class, arrays.toArray(named)))));
	}

	@Test
	void eachShapeRoundTripsAtAnUpperBoundedWildcardArgument() {
		PubList<String> pub = new PubList<>();
		pub.value = List.of("pub");
		PairList<String> pair = new PairList<>();
		pair.setValue(List.of("pair"));
		HoldsExtending extending = new HoldsExtending(new RecList<>(List.of("l0", "l1")), new RecMap<>(Map.of("k", "v")),
				new RecNested<>(List.of(List.of("n0"), List.of())), new RecBare<>("bare"),
				new RecArray<>(new String[] { "a0" }), new RecOpt<>(Optional.of("opt")),
				new RecOptList<>(Optional.of(List.of("o0"))), new RecBridged<>("bridged"), new CtorList<>(List.of("ctor")),
				new NamedList<>(List.of("named")), pub, pair, new MergedList<>(List.of("merged")));
		String expected = "[l0, l1] {k=v} [[n0], []] bare [a0] Optional[opt] Optional[[o0]] bridged [ctor] [named] [pub] "
				+ "[pair] [merged]";
		assertAll(() -> assertEquals(expected, values(extending)),
				() -> assertEquals(expected,
						values((HoldsExtending) maps.toObject(HoldsExtending.class, maps.toMap(extending)))),
				() -> assertEquals(expected, values(arrays.toObject(HoldsExtending.class, arrays.toArray(extending)))));
	}

	@Test
	void aProfileConstructorTakesEachShapeAtAnUpperBoundedWildcardArgument() {
		DataBindContext profiled = DataBindContext.builder().profile("p").build();
		MapMapper maps = new MapMapper(profiled);
		ArrayMapper arrays = new ArrayMapper(profiled);
		HoldsProfExtending extending = new HoldsProfExtending(new ProfList<>(List.of("l0")),
				new ProfMap<>(Map.of("k", "v")));
		assertAll(
				() -> assertEquals("[l0] {k=v}", values((HoldsProfExtending) maps.toObject(HoldsProfExtending.class,
						maps.toMap(extending)))),
				() -> assertEquals("[l0] {k=v}",
						values(arrays.toObject(HoldsProfExtending.class, arrays.toArray(extending)))));
	}

	private static String values(HoldsExtending held) {
		return String.join(" ", held.list().value().toString(), held.map().value().toString(),
				held.nested().value().toString(), held.bare().value(), Arrays.toString(held.array().value()),
				held.opt().value().toString(), held.optList().value().toString(), held.bridged().value(),
				held.ctor().getValue().toString(), held.named().getValue().toString(), held.pub().value.toString(),
				held.pair().getValue().toString(), held.merged().getValue().toString());
	}

	private static String values(HoldsProfExtending held) {
		return held.list().value() + " " + held.map().value();
	}

	private static String values(HoldsAllNamed named) {
		return String.join(" ", named.list().getValue().toString(), named.map().getValue().toString(),
				named.bare().getValue(), named.opt().getValue().toString(), named.plain().getValue().toString());
	}

	private static String values(HoldsBares bares) {
		return String.join(" ", bares.rec().value(), Arrays.toString(bares.recArray().value()),
				bares.ctor().getValue(), Arrays.toString(bares.ctorArray().getValue()), bares.fieldAccessor().value(),
				bares.pub().value, Arrays.toString(bares.pubArray().value), bares.pair().getValue(),
				Arrays.toString(bares.pairArray().getValue()), bares.merged().getValue(),
				Arrays.toString(bares.mergedArray().getValue()), bares.pubList().value.toString());
	}

	private static String values(HoldsProfBares bares) {
		return bares.bare().value() + " " + Arrays.toString(bares.array().value());
	}
}

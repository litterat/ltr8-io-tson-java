/*
 * Copyright (c) 2021, Litterat Pty Ltd. All Rights Reserved.
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;

/**
 * A model whose type graph contains a cycle resolves. Descriptors are cached only once resolution
 * <em>completes</em> and every component's type is resolved eagerly, so before cycle protection a type that
 * reached itself recursed until the stack went -- and the failure was a {@link StackOverflowError} out of
 * {@code DefaultRecordBinder}, not a diagnosable error.
 *
 * <p><b>A cyclic type graph does not mean an infinite value.</b> {@code Node -> Optional<Node>} describes a
 * list that ends wherever a value says it ends, and the AST this was found through -- {@code DataValue ->
 * CoreValue -> RecordValue -> ScopedValue -> DataValue} -- describes an ordinary finite document. Resolution
 * follows the types and so must be broken deliberately; reading and writing follow the values and terminate
 * on their own.
 *
 * <p>The shapes below are the two that matter: a record reaching itself directly, and a pair reaching each
 * other through a sealed union, which is the shape of every real cyclic model in this repo.
 */
public class RecursiveModelTest {

	/** A record that reaches itself through an {@code Optional} component. */
	public record Node(String name, Optional<Node> next) {
	}

	/** Two records reaching each other -- neither can be resolved without the other. */
	public record Parent(String tag, Optional<Child> child) {
	}

	public record Child(String tag, List<Parent> parents) {
	}

	/** The same cycle taking a hop through a sealed union, which is the AST's own shape. */
	public sealed interface Body permits Leaf, Nest {
	}

	public record Leaf(String text) implements Body {
	}

	public record Nest(List<Holder> items) implements Body {
	}

	public record Holder(String tag, Body body) {
	}

	/** The cycle closing through a map's value, a tuple's element, and an annotated position. */
	public record Keyed(String tag, Map<String, Keyed> children) {
	}

	public record Pair(String tag, Nested nested) {
	}

	public record Nested(Pair left, Pair right) {
	}

	private DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void aRecordReachingItselfResolves() throws DataBindException {
		DataClassRecord node = (DataClassRecord) context.getDescriptor(Node.class);

		DataClassField next = fieldNamed(node, "next");
		Assertions.assertSame(node, next.dataClass(),
				"the cyclic edge resolves to the very descriptor being built, not a second copy of it");
	}

	@Test
	public void twoRecordsReachingEachOtherResolve() throws DataBindException {
		DataClassRecord parent = (DataClassRecord) context.getDescriptor(Parent.class);

		DataClassRecord child = (DataClassRecord) fieldNamed(parent, "child").dataClass();
		DataClassArray parents = (DataClassArray) fieldNamed(child, "parents").dataClass();

		Assertions.assertSame(parent, parents.arrayDataClass(),
				"the edge back is the descriptor already in hand, whichever of the two was asked for first");
	}

	/**
	 * The same cycle through a union, which needs no protection of its own and is pinned so that stays true:
	 * a union descriptor holds its members as <em>classes</em>, resolved when one is used rather than when the
	 * union is described, so the edge through it is already lazy.
	 */
	@Test
	public void aCycleThroughAUnionResolvesToo() throws DataBindException {
		DataClassRecord holder = (DataClassRecord) context.getDescriptor(Holder.class);

		DataClassUnion body = (DataClassUnion) fieldNamed(holder, "body").dataClass();
		DataClassRecord nest = (DataClassRecord) context.getDescriptor(Nest.class);

		Assertions.assertEquals(2, body.memberTypes().length, "both branches of the union are found");
		Assertions.assertNotNull(fieldNamed(nest, "items").dataClass(), "and the branch that closes the cycle");
	}

	/**
	 * The descriptor a cyclic edge yields is the same object the cache holds, so a consumer walking the graph
	 * twice does not see two descriptions of one class -- which is what would make a deferred edge a leak
	 * rather than a repair.
	 */
	@Test
	public void aCyclicEdgeYieldsTheCachedDescriptor() throws DataBindException {
		DataClassRecord node = (DataClassRecord) context.getDescriptor(Node.class);

		Assertions.assertSame(context.getDescriptor(Node.class), fieldNamed(node, "next").dataClass());
	}

	/**
	 * A cycle closing through a map's value rather than through a field or an array. Each component kind
	 * resolves its own descriptors, so each is its own path to the same defect -- and a fix that converts
	 * only the paths a first test happens to walk leaves the rest to fail later.
	 */
	@Test
	public void aCycleThroughAMapValueResolves() throws DataBindException {
		DataClassRecord keyed = (DataClassRecord) context.getDescriptor(Keyed.class);

		DataClassMap children = (DataClassMap) fieldNamed(keyed, "children").dataClass();
		Assertions.assertSame(keyed, children.valueDataClass(), "the map's value closes the cycle");
		Assertions.assertNotNull(children.keyDataClass(), "and its key resolves as it always did");
	}

	/** The same, closing through a tuple's elements -- a record bound as a tuple by its own components. */
	@Test
	public void aCycleThroughATupleElementResolves() throws DataBindException {
		DataClassRecord pair = (DataClassRecord) context.getDescriptor(Pair.class);

		DataClassRecord nested = (DataClassRecord) fieldNamed(pair, "nested").dataClass();
		Assertions.assertSame(pair, fieldNamed(nested, "left").dataClass());
		Assertions.assertSame(pair, fieldNamed(nested, "right").dataClass());
	}

	private static DataClassField fieldNamed(DataClassRecord record, String name) {
		for (DataClassField field : record.fields()) {
			if (field.name().equals(name)) {
				return field;
			}
		}
		throw new IllegalArgumentException("no field '" + name + "' on " + record.typeClass());
	}
}

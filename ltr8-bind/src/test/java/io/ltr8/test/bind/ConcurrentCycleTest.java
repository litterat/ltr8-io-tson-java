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
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Threads resolving one cycle at once, each from a different member, leave the context with one descriptor per
 * class: every descriptor a cached one holds, across a cyclic edge or not, is the one the context hands out.
 */
public class ConcurrentCycleTest {

	public record Ring1(String tag, Ring2 next) {
	}

	public record Ring2(String tag, Ring3 next) {
	}

	public record Ring3(String tag, Ring4 next) {
	}

	public record Ring4(String tag, Ring5 next) {
	}

	public record Ring5(String tag, Ring6 next) {
	}

	public record Ring6(String tag, Optional<Ring1> next) {
	}

	public record Left(String tag, Optional<Right> right) {
	}

	public record Right(String tag, List<Left> lefts) {
	}

	/** A union holds its members as classes, so the cycle through it is broken at the union. */
	public sealed interface Expr permits Num, Add {
	}

	public record Num(int value) implements Expr {
	}

	public record Add(Expr left, Expr right, Optional<Add> next) implements Expr {
	}

	private static final List<Class<?>> TYPES = List.of(Ring1.class, Ring2.class, Ring3.class, Ring4.class,
			Ring5.class, Ring6.class, Left.class, Right.class, Add.class, Expr.class, Num.class);

	@Test
	void everyDescriptorACachedOneHoldsIsTheOneTheContextHandsOut() throws Exception {
		int threads = 8;
		int rounds = 2000;
		int split = 0;
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int round = 0; round < rounds; round++) {
				DataBindContext shared = DataBindContext.builder().build();
				CyclicBarrier start = new CyclicBarrier(threads);
				List<Future<?>> asks = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					List<Class<?>> order = new ArrayList<>(TYPES);
					Collections.rotate(order, t);
					asks.add(pool.submit(() -> {
						start.await();
						for (Class<?> type : order) {
							shared.getDescriptor(type);
						}
						return null;
					}));
				}
				for (Future<?> ask : asks) {
					ask.get();
				}
				if (!splitDescriptors(shared).isEmpty()) {
					split++;
				}
			}
		}
		assertEquals(0, split, "rounds leaving two descriptors for one class, of " + rounds);
	}

	/** Each place a descriptor reachable from the context's own holds one the context does not hand out. */
	private static List<String> splitDescriptors(DataBindContext context) throws DataBindException {
		List<String> split = new ArrayList<>();
		Set<DataClass> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<DataClass> pending = new ArrayList<>();
		for (Class<?> type : TYPES) {
			pending.add(context.getDescriptor(type));
		}
		while (!pending.isEmpty()) {
			DataClass at = pending.removeLast();
			if (!seen.add(at)) {
				continue;
			}
			List<DataClass> held = new ArrayList<>();
			if (at instanceof DataClassRecord record) {
				for (DataClassField field : record.fields()) {
					held.add(field.dataClass());
				}
			} else if (at instanceof DataClassArray array) {
				held.add(array.arrayDataClass());
			}
			for (DataClass component : held) {
				if (component instanceof DataClassRecord || component instanceof DataClassUnion) {
					if (component != context.getDescriptor(component.typeClass())) {
						split.add(at.typeClass().getSimpleName() + " holds a second " + component.typeClass());
					}
				}
				pending.add(component);
			}
		}
		return split;
	}
}

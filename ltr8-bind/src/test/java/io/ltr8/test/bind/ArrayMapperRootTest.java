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
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.test.data.SimpleEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Both {@link ArrayMapper} writes return an array, so each refuses a root written as one value. */
class ArrayMapperRootTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final ArrayMapper arrays = new ArrayMapper(context);

	@Test
	void anAtomAtTheRootIsRefusedByBothWrites() {
		assertAll(
				refused("java.lang.String", () -> arrays.toArray("a")),
				refused("java.lang.String", () -> arrays.toArray(context.getDescriptor(String.class), "a")),
				refused(SimpleEnum.class.getName(), () -> arrays.toArray(SimpleEnum.ONE)),
				refused(SimpleEnum.class.getName(),
						() -> arrays.toArray(context.getDescriptor(SimpleEnum.class), SimpleEnum.ONE)));
	}

	private static Executable refused(String type, Executable write) {
		return () -> assertEquals(type + " is not written as an array: an ArrayMapper writes an atom only inside a "
				+ "record, tuple, list, map or union", assertThrows(DataBindException.class, write).getMessage());
	}
}

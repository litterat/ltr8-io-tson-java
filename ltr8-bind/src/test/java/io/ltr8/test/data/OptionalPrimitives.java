/*
 * Copyright (c) 2021, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ltr8.test.data;

import io.ltr8.annotation.Record;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class OptionalPrimitives {

	private final OptionalInt optionalInt;
	private final OptionalLong optionalLong;
	private final OptionalDouble optionalDouble;

	@Record
	public OptionalPrimitives(OptionalInt optionalInt, OptionalLong optionalLong, OptionalDouble optionalDouble) {
		this.optionalInt = optionalInt;
		this.optionalLong = optionalLong;
		this.optionalDouble = optionalDouble;
	}

	public OptionalInt optionalInt() {
		return optionalInt;
	}

	public OptionalLong optionalLong() {
		return optionalLong;
	}

	public OptionalDouble optionalDouble() {
		return optionalDouble;
	}

}

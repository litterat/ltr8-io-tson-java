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

/**
 * 
 * This is a class that has both a constructor and get/set methods. In this scenario the resolver
 * should pick the constructor with arguments rather than calling setters.
 *
 */

public class OptionalPrimitivesMixed {

	private OptionalInt optionalInt;
	private OptionalLong optionalLong;
	private OptionalDouble optionalDouble;

	@Record
	public OptionalPrimitivesMixed(OptionalInt optionalInt, OptionalLong optionalLong, OptionalDouble optionalDouble) {
		this.optionalInt = optionalInt;
		this.optionalLong = optionalLong;
		this.optionalDouble = optionalDouble;
	}

	public OptionalPrimitivesMixed() {
		// empty constructor.
	}

	public OptionalInt getOptionalInt() {
		return optionalInt;
	}

	public OptionalLong getOptionalLong() {
		return optionalLong;
	}

	public OptionalDouble getOptionalDouble() {
		return optionalDouble;
	}

	public void setOptionalInt(OptionalInt optionalInt) {
		this.optionalInt = optionalInt;
	}

	public void setOptionalLong(OptionalLong optionalLong) {
		this.optionalLong = optionalLong;
	}

	public void setOptionalDouble(OptionalDouble optionalDouble) {
		this.optionalDouble = optionalDouble;
	}
}

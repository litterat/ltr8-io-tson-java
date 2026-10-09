/*
 * Copyright (c) 2020-2021, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.test.data;

import io.ltr8.annotation.Record;

import java.util.regex.Pattern;

/**
 *
 * Sample of a class containing an immutable Pattern value. Pattern is part of the JDK and uses the
 * PatternStringBridge which must be registered to convert values to String atoms.
 *
 */
public class SimplePatternImmutable {

	private final Pattern first;
	private final Pattern second;

	@Record
	public SimplePatternImmutable(Pattern first, Pattern second) {
		this.first = first;
		this.second = second;
	}

	public Pattern first() {
		return this.first;
	}

	public Pattern second() {
		return second;
	}

	@Override
	public String toString() {
		return "SimplePatternImmutable [first=" + first + ", second=" + second + "]";
	}

}

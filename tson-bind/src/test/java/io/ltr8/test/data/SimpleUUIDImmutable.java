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

import java.util.UUID;

/**
 *
 * Sample of a class containing immutable UUID values. UUIDs are part of Java and use the UUIDBridge
 * which must be registered to convert values to String atoms.
 *
 */
public class SimpleUUIDImmutable {

	private final UUID first;
	private final UUID second;

	@Record
	public SimpleUUIDImmutable(UUID first, UUID second) {
		this.first = first;
		this.second = second;
	}

	public UUID first() {
		return this.first;
	}

	public UUID second() {
		return second;
	}

	@Override
	public String toString() {
		return "SimpleUUIDImmutable [first=" + first + ", second=" + second + "]";
	}

}

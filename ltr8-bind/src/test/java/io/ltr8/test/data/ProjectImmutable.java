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
import io.ltr8.annotation.ToData;

/**
 * This is an example of a class which uses the ToData interface to expose another class
 * "ProjectImmutableData" as the set of values to be used in the data.
 */
public class ProjectImmutable implements ToData<ProjectImmutable.ProjectImmutableData> {

	private final int x;
	private final int y;

	public static class ProjectImmutableData {

		private final int a;
		private final int b;

		@Record
		public ProjectImmutableData(int a, int b) {
			this.a = a;
			this.b = b;
		}

		public int a() {
			return a;
		}

		public int b() {
			return b;
		}

	}

	public ProjectImmutable(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public ProjectImmutable(ProjectImmutableData data) {
		this.x = data.a;
		this.y = data.b;
	}

	@Override
	public ProjectImmutableData toData() {
		return new ProjectImmutableData(x, y);
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	@Override
	public String toString() {
		return "ProjectImmutable [x=" + x + ", y=" + y + "]";
	}

}
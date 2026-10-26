/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
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

import io.ltr8.annotation.DataBridge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 *
 * Default Collection to array bridge.
 *
 */
public class UnmodifiableLinkedListBridge implements DataBridge<String[], Collection<String>> {

	@Override
	public String[] toData(Collection<String> b) {
		String[] values = new String[b.size()];
		return b.toArray(values);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Collection<String> toObject(String[] data) {
        List<String> list = new LinkedList(Arrays.asList(data));
		return Collections.unmodifiableList(list);
	}

}

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
package io.ltr8.bind.bridge;

import io.ltr8.annotation.DataBridge;

import java.util.regex.Pattern;

/**
 *
 * Bridge for java.util.regex.Pattern that converts to/from its source text (String). Not
 * auto-detected the way EnumStringBridge is for any Java enum -- Pattern has no equivalent
 * unambiguous JDK signal (no Class#isPattern()) for DefaultClassBinder to key off of -- so a
 * caller registers it explicitly, e.g. context.registerAtom(Pattern.class, new
 * PatternStringBridge()), the same override mechanism every auto-detected type already exposes.
 *
 * Note: Pattern has no equals()/hashCode() override, so two Pattern instances built from the same
 * source text are never equal() to each other; compare via pattern() (or toData(...)) instead.
 */
public class PatternStringBridge implements DataBridge<String, Pattern> {

	@Override
	public String toData(Pattern b) {
		return b.pattern();
	}

	@Override
	public Pattern toObject(String s) {
		return Pattern.compile(s);
	}

}

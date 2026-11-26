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
package io.ltr8.test.data;

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** One {@code @Field} bridge on each way a {@code @Record} class can expose a component. */
public final class BridgedFields {

	private BridgedFields() {
	}

	/** An {@code Integer} written as its decimal text; an absent value passes through as {@code null}. */
	public static class NumberAsText implements DataBridge<String, Integer> {

		@Override
		public String toData(Integer number) {
			return number == null ? null : number.toString();
		}

		@Override
		public Integer toObject(String text) {
			return text == null ? null : Integer.valueOf(text);
		}
	}

	@Record
	public static class GetterSetter {

		private Integer count;

		public GetterSetter() {
		}

		@Field(bridge = NumberAsText.class)
		public Integer getCount() {
			return count;
		}

		public void setCount(Integer count) {
			this.count = count;
		}
	}

	@Record
	public static class PublicField {

		@Field(bridge = NumberAsText.class)
		public Integer count;

		public PublicField() {
		}
	}

	public static class Constructed {

		@Field(bridge = NumberAsText.class)
		private final Integer count;

		@Record
		public Constructed(Integer count) {
			this.count = count;
		}

		public Integer count() {
			return count;
		}
	}

	/** {@link NumberAsText}, recording each instance built and each instance a conversion runs on. */
	public static class RecordingNumberAsText implements DataBridge<String, Integer> {

		public static final Set<RecordingNumberAsText> BUILT = ConcurrentHashMap.newKeySet();
		public static final Set<RecordingNumberAsText> CONVERTED = ConcurrentHashMap.newKeySet();

		private final NumberAsText bridge = new NumberAsText();

		public RecordingNumberAsText() {
			BUILT.add(this);
		}

		@Override
		public String toData(Integer number) {
			CONVERTED.add(this);
			return bridge.toData(number);
		}

		@Override
		public Integer toObject(String text) {
			CONVERTED.add(this);
			return bridge.toObject(text);
		}
	}

	@Record
	public static class RecordedGetterSetter {

		private Integer count;

		public RecordedGetterSetter() {
		}

		@Field(bridge = RecordingNumberAsText.class)
		public Integer getCount() {
			return count;
		}

		public void setCount(Integer count) {
			this.count = count;
		}
	}

	@Record
	public static class RecordedPublicField {

		@Field(bridge = RecordingNumberAsText.class)
		public Integer count;

		public RecordedPublicField() {
		}
	}

	public record RecordedConstructed(@Field(bridge = RecordingNumberAsText.class) Integer count) {
	}

	public record TwoRecordedFields(@Field(bridge = RecordingNumberAsText.class) Integer first,
			@Field(bridge = RecordingNumberAsText.class) Integer second) {
	}

	// --- a bridge reaching DataBridge other than by naming it first among its own interfaces

	public abstract static class TextBridge<O> implements DataBridge<String, O> {
	}

	public static class InheritedNumberAsText extends TextBridge<Integer> {

		private final NumberAsText bridge = new NumberAsText();

		@Override
		public String toData(Integer number) {
			return bridge.toData(number);
		}

		@Override
		public Integer toObject(String text) {
			return bridge.toObject(text);
		}
	}

	/** The type parameters listed in the other order from {@code DataBridge}'s, and bound one hop apart. */
	public abstract static class Swapped<O, D> implements DataBridge<D, O> {
	}

	public abstract static class HalfBound<O> extends Swapped<O, String> {
	}

	public static class TwoHopNumberAsText extends HalfBound<Integer> {

		private final NumberAsText bridge = new NumberAsText();

		@Override
		public String toData(Integer number) {
			return bridge.toData(number);
		}

		@Override
		public Integer toObject(String text) {
			return bridge.toObject(text);
		}
	}

	public static class SubclassedNumberAsText extends NumberAsText {
	}

	public static class SerializableNumberAsText extends NumberAsText
			implements Serializable, DataBridge<String, Integer> {
	}

	public static class FunctionNumberAsText extends NumberAsText
			implements Function<Integer, String>, DataBridge<String, Integer> {

		@Override
		public String apply(Integer number) {
			return toData(number);
		}
	}

	public interface TextDataBridge<O> extends DataBridge<String, O> {
	}

	public static class SubInterfaceNumberAsText implements TextDataBridge<Integer> {

		private final NumberAsText bridge = new NumberAsText();

		@Override
		public String toData(Integer number) {
			return bridge.toData(number);
		}

		@Override
		public Integer toObject(String text) {
			return bridge.toObject(text);
		}
	}

	public record DirectBridge(@Field(bridge = NumberAsText.class) Integer count) {
	}

	public record AbstractBaseBridge(@Field(bridge = InheritedNumberAsText.class) Integer count) {
	}

	public record TwoHopBridge(@Field(bridge = TwoHopNumberAsText.class) Integer count) {
	}

	public record SubclassedBridge(@Field(bridge = SubclassedNumberAsText.class) Integer count) {
	}

	public record PlainInterfaceFirstBridge(@Field(bridge = SerializableNumberAsText.class) Integer count) {
	}

	public record GenericInterfaceFirstBridge(@Field(bridge = FunctionNumberAsText.class) Integer count) {
	}

	public record SubInterfaceBridge(@Field(bridge = SubInterfaceNumberAsText.class) Integer count) {
	}
}

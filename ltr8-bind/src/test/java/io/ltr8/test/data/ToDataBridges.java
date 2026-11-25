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

import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;

import java.util.List;

/**
 * Classes bridged to a record by {@link ToData}, each with a working {@code toData()}: the ones that declare
 * {@code ToData<S>} themselves, and the ways of reaching {@code ToData} that a bridge refuses.
 */
public final class ToDataBridges {

	private ToDataBridges() {
	}

	public record Problem(String title, int status) {
	}

	public record SkuNotFound(String sku) {
	}

	public record OutOfStock(String sku, int available) {
	}

	public static class SkuNotFoundException extends Exception implements ToData<SkuNotFound> {

		private final String sku;

		public SkuNotFoundException(SkuNotFound data) {
			super("no such sku " + data.sku());
			this.sku = data.sku();
		}

		public String sku() {
			return sku;
		}

		@Override
		public SkuNotFound toData() {
			return new SkuNotFound(sku);
		}
	}

	public abstract static class SkuFailure extends Exception {

		protected SkuFailure(String message) {
			super(message);
		}
	}

	public static class SkuMissing extends SkuFailure implements ToData<SkuNotFound> {

		private final String sku;

		public SkuMissing(SkuNotFound data) {
			super("missing " + data.sku());
			this.sku = data.sku();
		}

		public String sku() {
			return sku;
		}

		@Override
		public SkuNotFound toData() {
			return new SkuNotFound(sku);
		}
	}

	public static class SkuShort extends SkuFailure implements ToData<OutOfStock> {

		private final String sku;
		private final int available;

		public SkuShort(OutOfStock data) {
			super("short " + data.sku());
			this.sku = data.sku();
			this.available = data.available();
		}

		public String sku() {
			return sku;
		}

		public int available() {
			return available;
		}

		@Override
		public OutOfStock toData() {
			return new OutOfStock(sku, available);
		}
	}

	public static class ProblemBase implements ToData<Problem> {

		private final Problem problem;

		public ProblemBase(Problem problem) {
			this.problem = problem;
		}

		@Override
		public Problem toData() {
			return problem;
		}
	}

	public static class InheritsFromBase extends ProblemBase {

		public InheritsFromBase(Problem problem) {
			super(problem);
		}
	}

	public static class RedeclaresToData extends ProblemBase implements ToData<Problem> {

		public RedeclaresToData(Problem problem) {
			super(problem);
		}
	}

	public static class InheritsWithoutConstructor extends ProblemBase {

		public InheritsWithoutConstructor(String title) {
			super(new Problem(title, 500));
		}
	}

	public interface SkuError extends ToData<SkuNotFound> {
	}

	public static class InheritsFromInterface implements SkuError {

		private final String sku;

		public InheritsFromInterface(SkuNotFound data) {
			this.sku = data.sku();
		}

		@Override
		public SkuNotFound toData() {
			return new SkuNotFound(sku);
		}
	}

	public abstract static class AbstractWithConstructor implements ToData<SkuNotFound> {

		private final String sku;

		public AbstractWithConstructor(SkuNotFound data) {
			this.sku = data.sku();
		}

		@Override
		public SkuNotFound toData() {
			return new SkuNotFound(sku);
		}
	}

	public static class Carrier<S> implements ToData<S> {

		private final S data;

		public Carrier(S data) {
			this.data = data;
		}

		@Override
		public S toData() {
			return data;
		}
	}

	public static class InheritsThroughTypeArgument extends Carrier<SkuNotFound> {

		public InheritsThroughTypeArgument(SkuNotFound data) {
			super(data);
		}
	}

	public static class InheritsUnboundTypeVariable<T> extends Carrier<T> {

		public InheritsUnboundTypeVariable(T data) {
			super(data);
		}
	}

	@SuppressWarnings("rawtypes")
	public static class RawToData implements ToData {

		public RawToData(Problem problem) {
		}

		@Override
		public Object toData() {
			return new Problem("raw", 500);
		}
	}

	public static class ParameterizedRecordType implements ToData<List<String>> {

		private final List<String> lines;

		public ParameterizedRecordType(List<String> lines) {
			this.lines = lines;
		}

		@Override
		public List<String> toData() {
			return lines;
		}
	}

	public static class TypeVariableRecordType<T> implements ToData<T> {

		private final T data;

		public TypeVariableRecordType(T data) {
			this.data = data;
		}

		@Override
		public T toData() {
			return data;
		}
	}

	public static class ProtectedConstructor implements ToData<Problem> {

		protected ProtectedConstructor(Problem problem) {
		}

		@Override
		public Problem toData() {
			return new Problem("protected", 500);
		}
	}

	public static class ConstructorTakesSupertype implements ToData<SkuNotFound> {

		public ConstructorTakesSupertype(Object data) {
		}

		@Override
		public SkuNotFound toData() {
			return new SkuNotFound("supertype");
		}
	}

	public static class Payload {

		private final String text;

		@Record
		public Payload(String text) {
			this.text = text;
		}

		public String text() {
			return text;
		}
	}

	public static class SpecialPayload extends Payload {

		public SpecialPayload(String text) {
			super(text);
		}
	}

	public static class ConstructorTakesSubtype implements ToData<Payload> {

		public ConstructorTakesSubtype(SpecialPayload data) {
		}

		@Override
		public Payload toData() {
			return new Payload("subtype");
		}
	}
}

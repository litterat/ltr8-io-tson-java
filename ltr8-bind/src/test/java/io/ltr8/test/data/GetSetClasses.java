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

import java.util.Optional;

/**
 * Hand-written {@code @Record} classes reaching components through public fields and getter/setter pairs, one shape
 * each. A class beside the shape it tests holds a public field {@code name}, so it has a component either way.
 */
public final class GetSetClasses {

	private GetSetClasses() {
	}

	// --- public fields

	public static class FinalFieldWithAccessor {

		public final String name;

		@Record
		public FinalFieldWithAccessor(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class FinalFieldBesideMutableField {

		public final String name;

		public int count;

		@Record
		public FinalFieldBesideMutableField(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class FinalFieldWithoutAccessor {

		public final String name;

		@Record
		public FinalFieldWithoutAccessor(String name) {
			this.name = name;
		}
	}

	public static class InlineFinalField {

		public final String fixed = "fixed";

		public String name;

		@Record
		public InlineFinalField() {
		}
	}

	public static class MutableFields {

		public String name;

		public int count;

		@Record
		public MutableFields() {
		}
	}

	public static class MutableFieldBoundByConstructor {

		public String name;

		@Record
		public MutableFieldBoundByConstructor(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class OptionalField {

		public Optional<String> note = Optional.empty();

		@Record
		public OptionalField() {
		}
	}

	public static class StaticField {

		public static String shared;

		public String name;

		@Record
		public StaticField() {
		}
	}

	// --- getter/setter pairs

	public static class PublicPair {

		private String value;

		@Record
		public PublicPair() {
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	/** Its {@code secret} methods let a test set and read the pair's value; they are no pair themselves. */
	public static class PrivatePairBesidePublicField {

		public String name;

		private String secret;

		@Record
		public PrivatePairBesidePublicField() {
		}

		private String getSecret() {
			return secret;
		}

		private void setSecret(String secret) {
			this.secret = secret;
		}

		public String secret() {
			return getSecret();
		}

		public void secret(String secret) {
			setSecret(secret);
		}
	}

	public static class PackagePrivatePair {

		public String name;

		private String secret;

		@Record
		public PackagePrivatePair() {
		}

		String getSecret() {
			return secret;
		}

		void setSecret(String secret) {
			this.secret = secret;
		}
	}

	public static class ProtectedPair {

		public String name;

		private String secret;

		@Record
		public ProtectedPair() {
		}

		protected String getSecret() {
			return secret;
		}

		protected void setSecret(String secret) {
			this.secret = secret;
		}
	}

	public static class PublicGetterPrivateSetter {

		public String name;

		private String secret;

		@Record
		public PublicGetterPrivateSetter() {
		}

		public String getSecret() {
			return secret;
		}

		@SuppressWarnings("unused")
		private void setSecret(String secret) {
			this.secret = secret;
		}
	}

	public static class PrivateGetterPublicSetter {

		public String name;

		private String secret;

		@Record
		public PrivateGetterPublicSetter() {
		}

		@SuppressWarnings("unused")
		private String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}
	}

	public static class PrivatePairAlone {

		private String value;

		@Record
		public PrivatePairAlone() {
		}

		@SuppressWarnings("unused")
		private String getValue() {
			return value;
		}

		@SuppressWarnings("unused")
		private void setValue(String value) {
			this.value = value;
		}
	}

	public static class PrivatePairOverConstructorName {

		private String name;

		@Record
		public PrivatePairOverConstructorName(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}

		@SuppressWarnings("unused")
		private String getName() {
			return name;
		}

		@SuppressWarnings("unused")
		private void setName(String name) {
			this.name = name;
		}
	}

	/** Its public getter is the accessor the constructor's analysis matches; its setter is private. */
	public static class PrivateSetterOverConstructorName {

		private String name;

		@Record
		public PrivateSetterOverConstructorName(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		@SuppressWarnings("unused")
		private void setName(String name) {
			this.name = name;
		}
	}

	public static class StaticPair {

		private static String global;

		public String name;

		@Record
		public StaticPair() {
		}

		public static String getGlobal() {
			return global;
		}

		public static void setGlobal(String value) {
			global = value;
		}
	}

	// --- which setter pairs with a getter

	/** Its {@code int} setter stores the number's text, so a pair through either setter round-trips. */
	public static class OverloadedSetter {

		private String value;

		@Record
		public OverloadedSetter() {
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}

		public void setValue(int value) {
			this.value = Integer.toString(value);
		}
	}

	public static class Holder<T> {

		private T value;

		public T getValue() {
			return value;
		}

		public void setValue(T value) {
			this.value = value;
		}
	}

	/** Overrides both halves of its super class's pair, so it also declares a bridge of each. */
	public static class TextHolder extends Holder<String> {

		@Record
		public TextHolder() {
		}

		@Override
		public String getValue() {
			return super.getValue();
		}

		@Override
		public void setValue(String value) {
			super.setValue(value);
		}
	}

	public static class TwoArgumentSetter {

		public String name;

		private String value;

		@Record
		public TwoArgumentSetter() {
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value, int times) {
			this.value = value.repeat(times);
		}
	}

	public static class WiderSetter {

		public String name;

		private String value;

		@Record
		public WiderSetter() {
		}

		public String getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = String.valueOf(value);
		}
	}

	public static class BoxedSetter {

		public String name;

		private int count;

		@Record
		public BoxedSetter() {
		}

		public int getCount() {
			return count;
		}

		public void setCount(Integer count) {
			this.count = count;
		}
	}
}

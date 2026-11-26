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

import io.ltr8.annotation.ToData;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderNotFoundException;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.ToDataBridges.Problem;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import io.ltr8.test.data.union.SealedInterfaceUnion;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Interfaces, and things that are not interfaces, that a protocol is not read from: one refused shape each. */
public final class RefusedProtocols {

	private RefusedProtocols() {
	}

	// --- methods

	public interface Overloaded {

		Order find(OrderRef request);

		Order find(NewOrder request);
	}

	public interface ReturnsObject {

		Object x();
	}

	public interface ReturnsString {

		String x();
	}

	/** One method inherited from two interfaces with two return types. */
	public interface InheritsTwoReturns extends ReturnsObject, ReturnsString {
	}

	public interface DeclaresX {

		Order x();
	}

	public interface AlsoDeclaresX {

		Order x();
	}

	/** One method inherited from two interfaces with one return type, which {@code getMethods()} lists twice. */
	public interface InheritsOneReturnTwice extends DeclaresX, AlsoDeclaresX {
	}

	public interface OwnTypeParameter {

		<R extends Record> R find(OrderRef request);
	}

	public interface Crud<T> {

		T read(OrderRef request);

		void write(T request);
	}

	/** {@code Orders extends Crud<Order>}: the inherited methods still name {@code T}. */
	public interface Inherited extends Crud<Order> {
	}

	public interface TypeVariableRequest<T extends Record> {

		Order write(T request);
	}

	public interface TypeVariableReturn<T> {

		T read(OrderRef request);
	}

	public interface TypeVariableInsideReturn<T> {

		List<T> read(OrderRef request);
	}

	public interface TypeVariableThrows<X extends Exception> {

		Order read(OrderRef request) throws X;
	}

	public interface TwoParameters {

		Order placeOrder(String sku, int quantity);
	}

	public interface AtomParameter {

		Order getOrder(String id);
	}

	public interface ListParameter {

		Order merge(List<Order> request);
	}

	public interface OptionalParameter {

		Order getOrder(Optional<OrderRef> request);
	}

	public interface UnionParameter {

		Order draw(SealedInterfaceUnion request);
	}

	public interface UndescribableParameter {

		Order run(Thread request);
	}

	// --- returns

	public interface ReturnsBoxedVoid {

		Void cancel(OrderRef request);
	}

	public interface ReturnsOptional {

		Optional<Order> getOrder(OrderRef request);
	}

	public interface ReturnsOptionalInt {

		OptionalInt count(OrderRef request);
	}

	public interface ReturnsFuture {

		Future<Order> getOrder(OrderRef request);
	}

	public interface ReturnsCompletableFuture {

		CompletableFuture<Order> getOrder(OrderRef request);
	}

	public interface ReturnsCompletionStage {

		CompletionStage<Order> getOrder(OrderRef request);
	}

	public interface ReturnsPublisher {

		Flow.Publisher<Order> getOrders(OrderRef request);
	}

	public interface ReturnsStream {

		Stream<Order> getOrders(OrderRef request);
	}

	public interface ReturnsIntStream {

		IntStream counts(OrderRef request);
	}

	public interface ReturnsUndescribable {

		Thread getOrder(OrderRef request);
	}

	// --- throws

	public static class NotBridgedException extends Exception {

		public NotBridgedException(String message) {
			super(message);
		}
	}

	public interface ThrowsNotBridged {

		Order getOrder(OrderRef request) throws NotBridgedException;
	}

	public interface ThrowsUnchecked {

		Order getOrder(OrderRef request) throws IllegalArgumentException;
	}

	public interface ThrowsIOException {

		Order getOrder(OrderRef request) throws IOException;
	}

	/** Inherits {@code close() throws Exception}, a class no author can make a bridge. */
	public interface InheritsClose extends AutoCloseable {

		Order getOrder(OrderRef request);
	}

	public static class ProblemException extends Exception implements ToData<Problem> {

		private final Problem problem;

		public ProblemException(Problem problem) {
			this.problem = problem;
		}

		@Override
		public Problem toData() {
			return problem;
		}
	}

	public static class InheritedProblemException extends ProblemException {

		public InheritedProblemException(Problem problem) {
			super(problem);
		}
	}

	public interface ThrowsInheritingToData {

		Order getOrder(OrderRef request) throws InheritedProblemException;
	}

	public static class NoConstructorException extends Exception implements ToData<Problem> {

		public NoConstructorException(String title) {
			super(title);
		}

		@Override
		public Problem toData() {
			return new Problem(getMessage(), 500);
		}
	}

	public interface ThrowsWithoutConstructor {

		Order getOrder(OrderRef request) throws NoConstructorException;
	}

	public static class SpecialSkuNotFoundException extends SkuNotFoundException implements ToData<SkuNotFound> {

		public SpecialSkuNotFoundException(SkuNotFound data) {
			super(data);
		}
	}

	public interface ThrowsSubclassAfter {

		Order placeOrder(NewOrder request) throws SkuNotFoundException, SpecialSkuNotFoundException;
	}

	public interface ThrowsSubclassBefore {

		Order placeOrder(NewOrder request) throws SpecialSkuNotFoundException, SkuNotFoundException;
	}

	public static class OtherSkuNotFoundException extends Exception implements ToData<SkuNotFound> {

		private final SkuNotFound data;

		public OtherSkuNotFoundException(SkuNotFound data) {
			this.data = data;
		}

		@Override
		public SkuNotFound toData() {
			return data;
		}
	}

	public interface ThrowsTwoForOneRecord {

		Order placeOrder(NewOrder request) throws SkuNotFoundException, OrderNotFoundException,
				OtherSkuNotFoundException;
	}

	// --- names

	public interface Collides {

		Order placeOrder(NewOrder request);

		Order place_order(NewOrder request);
	}

	public interface HasGetOrder {

		Order getOrder(OrderRef request);
	}

	/** Collides with a method it inherits. */
	public interface CollidesInherited extends HasGetOrder {

		Order get_order(OrderRef request);
	}

	// --- not an interface

	public static class OrdersClass {

		public Order getOrder(OrderRef request) {
			return null;
		}
	}

	public abstract static class AbstractOrders {

		public abstract Order getOrder(OrderRef request);
	}

	public @interface OrdersAnnotation {

		String value();
	}

	public enum OrdersEnum {
		ONE
	}

	// --- not reachable through the public lookup

	interface PackagePrivate {

		Order getOrder(OrderRef request);
	}

	record HiddenRequest(String id) {
	}

	public interface NamesHiddenRequest {

		Order getOrder(HiddenRequest request);
	}

	public interface NamesHiddenInsideResponse {

		List<HiddenRequest> getOrders(OrderRef request);
	}
}

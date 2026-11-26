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
package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderNotFoundException;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.RefusedProtocols;
import io.ltr8.test.data.RefusedProtocols.AbstractOrders;
import io.ltr8.test.data.RefusedProtocols.AtomParameter;
import io.ltr8.test.data.RefusedProtocols.Collides;
import io.ltr8.test.data.RefusedProtocols.CollidesInherited;
import io.ltr8.test.data.RefusedProtocols.Crud;
import io.ltr8.test.data.RefusedProtocols.InheritedProblemException;
import io.ltr8.test.data.RefusedProtocols.InheritsClose;
import io.ltr8.test.data.RefusedProtocols.Inherited;
import io.ltr8.test.data.RefusedProtocols.InheritsOneReturnTwice;
import io.ltr8.test.data.RefusedProtocols.InheritsTwoReturns;
import io.ltr8.test.data.RefusedProtocols.ListParameter;
import io.ltr8.test.data.RefusedProtocols.NamesHiddenInsideResponse;
import io.ltr8.test.data.RefusedProtocols.NamesHiddenRequest;
import io.ltr8.test.data.RefusedProtocols.NoConstructorException;
import io.ltr8.test.data.RefusedProtocols.NotBridgedException;
import io.ltr8.test.data.RefusedProtocols.OptionalParameter;
import io.ltr8.test.data.RefusedProtocols.OrdersAnnotation;
import io.ltr8.test.data.RefusedProtocols.OrdersClass;
import io.ltr8.test.data.RefusedProtocols.OrdersEnum;
import io.ltr8.test.data.RefusedProtocols.OtherSkuNotFoundException;
import io.ltr8.test.data.RefusedProtocols.Overloaded;
import io.ltr8.test.data.RefusedProtocols.OwnTypeParameter;
import io.ltr8.test.data.RefusedProtocols.ReturnsCompletableFuture;
import io.ltr8.test.data.RefusedProtocols.ReturnsCompletionStage;
import io.ltr8.test.data.RefusedProtocols.ReturnsFuture;
import io.ltr8.test.data.RefusedProtocols.ReturnsIntStream;
import io.ltr8.test.data.RefusedProtocols.ReturnsBoxedVoid;
import io.ltr8.test.data.RefusedProtocols.ReturnsOptional;
import io.ltr8.test.data.RefusedProtocols.ReturnsOptionalInt;
import io.ltr8.test.data.RefusedProtocols.ReturnsPublisher;
import io.ltr8.test.data.RefusedProtocols.ReturnsStream;
import io.ltr8.test.data.RefusedProtocols.ReturnsUndescribable;
import io.ltr8.test.data.RefusedProtocols.SpecialSkuNotFoundException;
import io.ltr8.test.data.RefusedProtocols.ThrowsIOException;
import io.ltr8.test.data.RefusedProtocols.ThrowsInheritingToData;
import io.ltr8.test.data.RefusedProtocols.ThrowsNotBridged;
import io.ltr8.test.data.RefusedProtocols.ThrowsSubclassAfter;
import io.ltr8.test.data.RefusedProtocols.ThrowsSubclassBefore;
import io.ltr8.test.data.RefusedProtocols.ThrowsTwoForOneRecord;
import io.ltr8.test.data.RefusedProtocols.ThrowsUnchecked;
import io.ltr8.test.data.RefusedProtocols.ThrowsWithoutConstructor;
import io.ltr8.test.data.RefusedProtocols.TwoParameters;
import io.ltr8.test.data.RefusedProtocols.TypeVariableInsideReturn;
import io.ltr8.test.data.RefusedProtocols.TypeVariableRequest;
import io.ltr8.test.data.RefusedProtocols.TypeVariableReturn;
import io.ltr8.test.data.RefusedProtocols.TypeVariableThrows;
import io.ltr8.test.data.RefusedProtocols.UndescribableParameter;
import io.ltr8.test.data.RefusedProtocols.UnionParameter;
import io.ltr8.test.data.ToDataBridges.Problem;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import io.ltr8.test.data.union.SealedInterfaceUnion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.stream.BaseStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shape a protocol is not read from is refused when the protocol is built, with a {@link DataBindException}
 * whose own message names the interface, the method and what is wrong.
 */
class ProtocolRefusalTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- a protocol is read from an interface

	@Test
	void aClassIsRefused() {
		assertNames(refusal(OrdersClass.class), OrdersClass.class.getName(), "is a class", "interface");
		assertNames(refusal(AbstractOrders.class), AbstractOrders.class.getName(), "is a class", "interface");
	}

	@Test
	void anAnnotationInterfaceIsRefused() {
		assertNames(refusal(OrdersAnnotation.class), OrdersAnnotation.class.getName(), "is an annotation interface");
	}

	@Test
	void anEnumAndARecordAreRefused() {
		assertAll(
				() -> assertNames(refusal(OrdersEnum.class), OrdersEnum.class.getName(), "is an enum"),
				() -> assertNames(refusal(Order.class), Order.class.getName(), "is a record"));
	}

	@Test
	void aNullTypeIsANullPointerException() {
		assertThrows(NullPointerException.class, () -> context.getProtocol(null));
	}

	// --- one name, one method

	@Test
	void overloadsAreRefused() {
		assertNames(refusal(Overloaded.class), Overloaded.class.getName(), "two abstract methods named find",
				OrderRef.class.getName(), NewOrder.class.getName());
	}

	@Test
	void oneMethodInheritedWithTwoReturnTypesIsRefused() {
		assertNames(refusal(InheritsTwoReturns.class), InheritsTwoReturns.class.getName(),
				"two abstract methods named x", "java.lang.Object", "java.lang.String");
	}

	@Test
	void oneMethodInheritedFromTwoInterfacesWithOneReturnTypeIsRefused() {
		// Reflection lists two abstract methods; the rule refuses two abstract methods of one name.
		assertNames(refusal(InheritsOneReturnTwice.class), InheritsOneReturnTwice.class.getName(),
				"two abstract methods named x");
	}

	@Test
	void twoMethodsWhoseNamesAreOneUnderTheUnderscoreRuleAreRefused() {
		assertNames(refusal(Collides.class), Collides.class.getName(), "placeOrder", "place_order",
				"one name");
	}

	@Test
	void aMethodCollidingWithOneItInheritsIsRefused() {
		assertNames(refusal(CollidesInherited.class), CollidesInherited.class.getName(), "getOrder", "get_order",
				"one name");
	}

	// --- generics

	@Test
	void aMethodWithItsOwnTypeParametersIsRefused() {
		assertNames(refusal(OwnTypeParameter.class), OwnTypeParameter.class.getName() + ".find",
				"type parameters of its own", "R");
	}

	@Test
	void aTypeVariableRequestIsRefused() {
		assertNames(refusal(TypeVariableRequest.class), TypeVariableRequest.class.getName() + ".write",
				"request", "type variable T", TypeVariableRequest.class.getName());
	}

	@Test
	void aTypeVariableReturnIsRefused() {
		assertNames(refusal(TypeVariableReturn.class), TypeVariableReturn.class.getName() + ".read", "response",
				"type variable T");
	}

	@Test
	void aTypeVariableInsideAReturnIsRefused() {
		assertNames(refusal(TypeVariableInsideReturn.class), TypeVariableInsideReturn.class.getName() + ".read",
				"response", "java.util.List<T>", "type variable T");
	}

	@Test
	void aGenericSuperInterfacesTypeVariableIsRefusedOnTheInterfaceExtendingIt() {
		// Orders extends Crud<Order>: read returns T and write takes T, and neither is resolved to Order.
		String message = refusal(Inherited.class);
		assertNames(message, Inherited.class.getName(), "type variable T", Crud.class.getName());
		assertTrue(message.contains(".read's response") || message.contains(".write's request"), message);
	}

	@Test
	void aTypeVariableInTheThrowsClauseIsRefused() {
		assertNames(refusal(TypeVariableThrows.class), TypeVariableThrows.class.getName() + ".read", "throws X",
				"type variable");
	}

	// --- one record or nothing

	@Test
	void moreThanOneParameterIsRefused() {
		assertNames(refusal(TwoParameters.class), TwoParameters.class.getName() + ".placeOrder", "takes 2 parameters",
				"java.lang.String, int", "one record or nothing");
	}

	@Test
	void anAtomParameterIsRefused() {
		assertNames(refusal(AtomParameter.class), AtomParameter.class.getName() + ".getOrder", "java.lang.String",
				"describes as an atom", "request is a record");
	}

	@Test
	void aListParameterIsRefused() {
		assertNames(refusal(ListParameter.class), ListParameter.class.getName() + ".merge",
				"java.util.List<" + Order.class.getName() + ">", "describes as an array", "request is a record");
	}

	@Test
	void anOptionalParameterIsRefused() {
		String message = refusal(OptionalParameter.class);
		assertNames(message, OptionalParameter.class.getName() + ".getOrder", "request",
				"java.util.Optional<" + OrderRef.class.getName() + ">");
	}

	@Test
	void aUnionParameterIsRefused() {
		assertNames(refusal(UnionParameter.class), UnionParameter.class.getName() + ".draw",
				SealedInterfaceUnion.class.getName(), "describes as a union", "request is a record");
	}

	@Test
	void aParameterThatCannotBeDescribedIsRefusedWithTheReasonAndItsCause() {
		DataBindException thrown = refused(UndescribableParameter.class);
		assertNames(thrown.getMessage(), UndescribableParameter.class.getName() + ".run's request",
				"java.lang.Thread", "cannot be described", "Unable to find a valid data conversion");
		assertCauseIsTheDescriptorsRefusal(thrown, Thread.class);
	}

	@Test
	void aBoxedVoidReturnIsRefusedSayingToReturnVoid() {
		assertNames(refusal(ReturnsBoxedVoid.class), ReturnsBoxedVoid.class.getName() + ".cancel",
				"returns java.lang.Void, which has no value", "an exchange with no response returns void");
	}

	// --- a response is a value, not a promise of one

	@Test
	void eachPromiseTypedReturnIsRefused() {
		assertAll(
				() -> assertRefusedReturn(ReturnsOptional.class, "getOrder", Optional.class),
				() -> assertRefusedReturn(ReturnsOptionalInt.class, "count", OptionalInt.class),
				() -> assertRefusedReturn(ReturnsFuture.class, "getOrder", Future.class),
				() -> assertRefusedReturn(ReturnsCompletableFuture.class, "getOrder", Future.class),
				() -> assertRefusedReturn(ReturnsCompletionStage.class, "getOrder", CompletionStage.class),
				() -> assertRefusedReturn(ReturnsPublisher.class, "getOrders", Flow.Publisher.class),
				() -> assertRefusedReturn(ReturnsStream.class, "getOrders", BaseStream.class),
				() -> assertRefusedReturn(ReturnsIntStream.class, "counts", BaseStream.class));
	}

	@Test
	void aReturnThatCannotBeDescribedIsRefusedWithTheReasonAndItsCause() {
		DataBindException thrown = refused(ReturnsUndescribable.class);
		assertNames(thrown.getMessage(), ReturnsUndescribable.class.getName() + ".getOrder's response",
				"java.lang.Thread", "cannot be described", "Unable to find a valid data conversion");
		assertCauseIsTheDescriptorsRefusal(thrown, Thread.class);
	}

	// --- a throws clause is the declared errors, whole

	@Test
	void aCheckedExceptionThatDoesNotImplementToDataIsRefused() {
		assertAll(
				() -> assertNames(refusal(ThrowsNotBridged.class), ThrowsNotBridged.class.getName() + ".getOrder",
						"throws " + NotBridgedException.class.getName(), "does not implement ToData"),
				() -> assertNames(refusal(ThrowsIOException.class), ThrowsIOException.class.getName() + ".getOrder",
						"throws " + IOException.class.getName(), "does not implement ToData"));
	}

	@Test
	void anUncheckedExceptionThatDoesNotImplementToDataIsRefused() {
		assertNames(refusal(ThrowsUnchecked.class), ThrowsUnchecked.class.getName() + ".getOrder",
				"throws " + IllegalArgumentException.class.getName(), "does not implement ToData");
	}

	@Test
	void anInheritedThrowsClauseIsRefusedWithoutAdviceToChangeTheThrownClass() {
		String message = refusal(InheritsClose.class);
		assertNames(message, InheritsClose.class.getName() + ".close", "throws java.lang.Exception",
				"does not implement ToData");
		assertFalse(message.contains("declare implements"), message);
	}

	@Test
	void aThrowsTypeInheritingToDataIsRefusedWithTheBridgesReason() {
		DataBindException thrown = refused(ThrowsInheritingToData.class);
		assertNames(thrown.getMessage(), ThrowsInheritingToData.class.getName() + ".getOrder's error",
				InheritedProblemException.class.getName(), "cannot be described", "inherits ToData from",
				"implements ToData<" + Problem.class.getName() + ">");
		assertCauseIsTheDescriptorsRefusal(thrown, InheritedProblemException.class);
	}

	@Test
	void aThrowsTypeWithoutAConstructorTakingItsRecordIsRefusedWithTheBridgesReason() {
		DataBindException thrown = refused(ThrowsWithoutConstructor.class);
		assertNames(thrown.getMessage(), ThrowsWithoutConstructor.class.getName() + ".getOrder's error",
				NoConstructorException.class.getName(), "cannot be described",
				"no public constructor taking " + Problem.class.getName());
		assertCauseIsTheDescriptorsRefusal(thrown, NoConstructorException.class);
	}

	@Test
	void twoThrowsTypesRelatedBySubclassingAreRefusedInEitherOrder() {
		for (Class<?> type : List.of(ThrowsSubclassAfter.class, ThrowsSubclassBefore.class)) {
			assertNames(refusal(type), type.getName() + ".placeOrder", SkuNotFoundException.class.getName(),
					SpecialSkuNotFoundException.class.getName(), SpecialSkuNotFoundException.class.getName()
							+ " is a subclass of " + SkuNotFoundException.class.getName());
		}
	}

	@Test
	void twoThrowsTypesBridgingOneRecordAreRefused() {
		String message = refusal(ThrowsTwoForOneRecord.class);
		assertNames(message, ThrowsTwoForOneRecord.class.getName() + ".placeOrder",
				SkuNotFoundException.class.getName(), OtherSkuNotFoundException.class.getName(),
				"both bridge to " + SkuNotFound.class.getName());
		assertFalse(message.contains(OrderNotFoundException.class.getName()), message);
	}

	// --- public, through the public lookup

	@Test
	void anInterfaceThatIsNotPublicIsRefused() throws ClassNotFoundException {
		Class<?> packagePrivate = Class.forName(RefusedProtocols.class.getName() + "$PackagePrivate");
		assertNames(refusal(packagePrivate), packagePrivate.getName(), "is not public", "public lookup");
	}

	@Test
	void aRequestClassThatIsNotPublicIsRefusedNamingIt() {
		assertNames(refusal(NamesHiddenRequest.class), NamesHiddenRequest.class.getName() + ".getOrder's request",
				RefusedProtocols.class.getName() + "$HiddenRequest", "is not public", "public lookup");
	}

	@Test
	void aClassNamedInsideAResponseThatIsNotPublicIsRefusedNamingIt() {
		assertNames(refusal(NamesHiddenInsideResponse.class),
				NamesHiddenInsideResponse.class.getName() + ".getOrders's response",
				RefusedProtocols.class.getName() + "$HiddenRequest", "is not public");
	}

	private void assertRefusedReturn(Class<?> type, String method, Class<?> family) {
		assertNames(refusal(type), type.getName() + "." + method + " returns", "a " + family.getName(),
				"not a promise of one");
	}

	private void assertCauseIsTheDescriptorsRefusal(DataBindException thrown, Class<?> type) {
		DataBindException direct = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
		assertNotNull(thrown.getCause());
		assertSame(DataBindException.class, thrown.getCause().getClass());
		assertTrue(thrown.getMessage().endsWith(direct.getMessage()),
				() -> "expected the descriptor's own message '" + direct.getMessage() + "' in:\n"
						+ thrown.getMessage());
	}

	private DataBindException refused(Class<?> type) {
		return assertThrows(DataBindException.class, () -> context.getProtocol(type));
	}

	/** The refusal's own message: it names everything itself, so the cause chain is not consulted. */
	private String refusal(Class<?> type) {
		return refused(type).getMessage();
	}

	static void assertNames(String message, String... facts) {
		for (String fact : facts) {
			assertTrue(message.contains(fact), () -> "expected '" + fact + "' in:\n" + message);
		}
	}
}

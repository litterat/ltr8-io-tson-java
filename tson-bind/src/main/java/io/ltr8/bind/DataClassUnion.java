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
package io.ltr8.bind;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;

/**
 * 
 * A Union data class is tagged union type. It can be represented in a number of ways in Java:
 * 
 * <ul>
 * <li>interface: An interface allows multiple data representations as child classes.
 * <li>abstract class: Similar to an interface, an abstract or base class can have multiple data
 * representations.
 * <li>embedded union: A class with one or more fields where only one is present at any one time.
 * </ul>
 *
 * The members are the actual classes instead of a DataClass. This is to ensure that infinite
 * resolution loops do not occur.
 */
public class DataClassUnion extends DataClass {

	/**
	 * Volatile because {@link #addMemberType} publishes a whole new array (copy-on-write under this
	 * object's own lock) while readers take an unlocked snapshot: without it a reader has no
	 * happens-before with the writer and may never see an added member at all. Every read takes a local
	 * copy of the reference first, so one read never sees the array change under it.
	 */
	private volatile Class<?>[] memberTypes;

	private final boolean isSealed;

	public DataClassUnion(Class<?> targetType, Class<?>[] members, boolean isSealed) {
		super( targetType);

		this.memberTypes = Objects.requireNonNull(members);
		this.isSealed = isSealed;
	}

	public Class<?>[] memberTypes() {
		return memberTypes;
	}

	public boolean isSealed() {
		return isSealed;
	}

	/**
	 * Whether {@code dataClass} is a member of this union.
	 *
	 * <p><b>An open member stands for its implementations.</b> A union collected from a sealed hierarchy
	 * flattens the sealed branches to their leaves but keeps a <em>non-sealed</em> permitted type as a member
	 * in its own right -- its implementations cannot be known at analysis time, which is why such a union is
	 * built extensible and why {@link #addMemberType} exists. Asking only whether the exact class is already
	 * listed therefore answers "no" for every one of those implementations, and the member that was meant to
	 * stand for them never gets consulted. So a candidate assignable to an open (non-final) member is a
	 * member, and is <b>registered here</b> so later calls, and {@link #memberTypes}, find it directly.
	 *
	 * <p>Registration is what makes such a member usable rather than merely acknowledged: a caller resolves
	 * the class's own descriptor from here, so an implementation is written and read as itself rather than as
	 * the abstract member it arrived under. It is memoisation, not a change of answer -- the same question
	 * asked twice gives the same result, and {@link #memberTypes} is the view that grows.
	 *
	 * <p>A candidate matching two open members is refused: nothing here can say which was meant, and choosing
	 * either would make the answer depend on member order.
	 */
	public boolean isMemberType(Class<?> dataClass) {

		// The common case, lock-free: one read of the volatile field and a scan of that snapshot.
		if (contains(dataClass)) {
			return true;
		}

		if (dataClass == null || isSealed) {
			return false;
		}

		return admitOpenImplementation(dataClass);
	}

	/**
	 * Whether {@code dataClass} is <em>already listed</em>. The plain lookup {@link #isMemberType} used to
	 * be, kept private: every caller asking about membership wants the real question, and the only two
	 * places that want the listing itself are {@link #addMemberType}'s own guard -- which would otherwise
	 * recurse, since {@link #isMemberType} may now register -- and {@link #memberTypes}, which exposes the
	 * list directly.
	 */
	private boolean contains(Class<?> dataClass) {

		// addMemberType replaces the array rather than mutating it, so a snapshot is always internally
		// consistent and one read never sees it change underneath.
		Class<?>[] types = this.memberTypes;
		for (Class<?> dClass : types) {
			if (dClass.equals(dataClass)) {
				return true;
			}
		}

		return false;
	}

	/** {@link #isMemberType}'s slow path -- taken only on a miss, so the common case stays lock-free. */
	private synchronized boolean admitOpenImplementation(Class<?> dataClass) {

		if (contains(dataClass)) {
			return true; // registered between the lock-free miss and this lock
		}

		// One read of the volatile field, as everywhere else here -- redundant under the lock, but the
		// snapshot idiom is what says at a glance that nothing in this loop can shift underneath it.
		Class<?>[] types = this.memberTypes;

		Class<?> openMember = null;
		for (Class<?> member : types) {
			if (member != dataClass && !Modifier.isFinal(member.getModifiers())
					&& member.isAssignableFrom(dataClass)) {
				if (openMember != null) {
					return false; // ambiguous -- see this method's caller
				}
				openMember = member;
			}
		}

		if (openMember == null) {
			return false;
		}

		appendMemberType(dataClass);
		return true;
	}

	/** Grows the member array by one. The caller has already established that the union admits additions. */
	private synchronized void appendMemberType(Class<?> newType) {

		Class<?>[] types = this.memberTypes;

		Class<?>[] newMemberTypes = Arrays.copyOf(types, types.length + 1);
		newMemberTypes[newMemberTypes.length - 1] = newType;

		this.memberTypes = newMemberTypes;
	}

	/**
	 * As different implementations of an interface or abstract class will get loaded at different times
	 * the list of union types will not all be known at startup. Therefore, it needs to be possible to
	 * add additional implementations to the list. One of the reasons why sealed classes are a better
	 * choice.
	 * 
	 * @throws DataBindException when the union is sealed and new members can't be added.
	 */
	public synchronized void addMemberType(Class<?> newType) throws DataBindException {

		// contains(), not isMemberType(): the latter may register, which would recurse back into here.
		if (contains(newType)) {
			return;
		}

		if (isSealed) {
			throw new DataBindException("Union type is sealed. No addition member types can be added.");
		}

		appendMemberType(newType);
	}

	public Object checkIsMember(Object value) throws DataBindException {

		if (value == null) {
			return value;
		}

		if (isMemberType(value.getClass())) {
			return value;
		}

		throw new DataBindException(String.format("Value '%s' not in valid types %s", value.getClass().getName(),
				membersToString(memberTypes)));
	}

	@Override
	public String toString() {
		return "DataClassUnion [typeClass=" + typeClass().getName() + ", memberTypes=" + membersToString(memberTypes)
				+ ", isSealed=" + isSealed + "]";
	}

	private String membersToString(Class<?>[] dataClass) {

		if (dataClass == null || dataClass.length == 0) {
			return "[]";
		}

		StringBuilder b = new StringBuilder();
		b.append('[');
		for (int x = 0; x < dataClass.length; x++) {
			b.append(String.valueOf(dataClass[x].getName()));
			if (x == dataClass.length - 1) {
				break;
			}
			b.append(", ");
		}
		b.append(']');
		return b.toString();
	}

}

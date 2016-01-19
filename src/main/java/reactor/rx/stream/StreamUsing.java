/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.rx.stream;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.error.Exceptions;
import reactor.core.subscription.EmptySubscription;
import reactor.core.support.BackpressureUtils;
import reactor.core.support.ReactiveState;
import reactor.fn.Consumer;
import reactor.fn.Function;

/**
 * Uses a resource, generated by a supplier for each individual Subscriber,
 * while streaming the values from a
 * Publisher derived from the same resource and makes sure the resource is released
 * if the sequence terminates or the Subscriber cancels.
 * <p>
 * <p>
 * Eager resource cleanup happens just before the source termination and exceptions
 * raised by the cleanup Consumer may override the terminal even. Non-eager
 * cleanup will drop any exception.
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamUsing<T, S> 
extends reactor.rx.Stream<T>
implements 
												   ReactiveState.Factory,
												   ReactiveState.Upstream {

	final Callable<S> resourceSupplier;

	final Function<? super S, ? extends Publisher<? extends T>> sourceFactory;

	final Consumer<? super S> resourceCleanup;

	final boolean eager;

	public StreamUsing(Callable<S> resourceSupplier,
						  Function<? super S, ? extends Publisher<? extends T>> sourceFactory, Consumer<? super S>
								  resourceCleanup,
						  boolean eager) {
		this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
		this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
		this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
		this.eager = eager;
	}

	@Override
	public Object upstream() {
		return resourceSupplier;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		S resource;

		try {
			resource = resourceSupplier.call();
		} catch (Throwable e) {
			Exceptions.throwIfFatal(e);
			EmptySubscription.error(s, Exceptions.unwrap(e));
			return;
		}

		Publisher<? extends T> p;

		try {
			p = sourceFactory.apply(resource);
		} catch (Throwable e) {

			try {
				resourceCleanup.accept(resource);
			} catch (Throwable ex) {
				Exceptions.throwIfFatal(ex);
				ex.addSuppressed(Exceptions.unwrap(e));
				e = ex;
			}

			EmptySubscription.error(s, Exceptions.unwrap(e));
			return;
		}

		if (p == null) {
			Throwable e = new NullPointerException("The sourceFactory returned a null value");
			try {
				resourceCleanup.accept(resource);
			} catch (Throwable ex) {
				Throwable _ex = Exceptions.unwrap(ex);
				_ex.addSuppressed(e);
				Exceptions.throwIfFatal(ex);
				e = _ex;
			}

			EmptySubscription.error(s, e);
			return;
		}

		p.subscribe(new StreamUsingSubscriber<>(s, resourceCleanup, resource, eager));
	}

	static final class StreamUsingSubscriber<T, S>
	  implements Subscriber<T>, Subscription {

		final Subscriber<? super T> actual;

		final Consumer<? super S> resourceCleanup;

		final S resource;

		final boolean eager;

		Subscription s;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamUsingSubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(StreamUsingSubscriber.class, "wip");

		public StreamUsingSubscriber(Subscriber<? super T> actual, Consumer<? super S> resourceCleanup, S
				resource, boolean eager) {
			this.actual = actual;
			this.resourceCleanup = resourceCleanup;
			this.resource = resource;
			this.eager = eager;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			if (WIP.compareAndSet(this, 0, 1)) {
				s.cancel();

				cleanup();
			}
		}

		void cleanup() {
			try {
				resourceCleanup.accept(resource);
			} catch (Throwable e) {
				Exceptions.onErrorDropped(e);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (eager) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					Throwable _e = Exceptions.unwrap(e);
					_e.addSuppressed(t);
					t = _e;
				}
			}

			actual.onError(t);

			if (!eager) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (eager) {
				try {
					resourceCleanup.accept(resource);
				} catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					actual.onError(Exceptions.unwrap(e));
					return;
				}
			}

			actual.onComplete();

			if (!eager) {
				cleanup();
			}
		}
	}
}

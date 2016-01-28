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
package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Loopback;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;
import reactor.core.state.Completable;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;
import reactor.fn.Function;

/**
 * Maps the values of the source publisher one-on-one via a mapper function.
 * <p>
 * This variant allows composing fuseable stages.
 * 
 * @param <T> the source value type
 * @param <R> the result value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class StreamMapFuseable<T, R> extends StreamSource<T, R>
		implements Fuseable {

	final Function<? super T, ? extends R> mapper;

	/**
	 * Constructs a StreamMap instance with the given source and mapper.
	 *
	 * @param source the source Publisher instance
	 * @param mapper the mapper function
	 * @throws NullPointerException if either {@code source} or {@code mapper} is null.
	 */
	public StreamMapFuseable(Publisher<? extends T> source, Function<? super T, ? extends R> mapper) {
		super(source);
		if (!(source instanceof Fuseable)) {
			throw new IllegalArgumentException("The source must implement the Fuseable interface for this operator to work");
		}
		this.mapper = Objects.requireNonNull(mapper, "mapper");
	}

	public Function<? super T, ? extends R> mapper() {
		return mapper;
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {
		source.subscribe(new MapFuseableSubscriber<>(s, mapper));
	}

	static final class MapFuseableSubscriber<T, R> 
	extends SynchronousSubscription<R>
	implements Subscriber<T>, Completable, Receiver, Producer, Loopback, Subscription {
		final Subscriber<? super R>			actual;
		final Function<? super T, ? extends R> mapper;

		boolean done;

		QueueSubscription<T> s;

		int sourceMode;

		/** Running with regular, arbitrary source. */
		static final int NORMAL = 0;
		/** Running with a source that implements SynchronousSource. */
		static final int SYNC = 1;
		/** Running with a source that implements AsynchronousSource. */
		static final int ASYNC = 2;
		
		public MapFuseableSubscriber(Subscriber<? super R> actual, Function<? super T, ? extends R> mapper) {
			this.actual = actual;
			this.mapper = mapper;
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = (QueueSubscription<T>)s;

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Exceptions.onNextDropped(t);
				return;
			}

			int m = sourceMode;
			
			if (m == 0) {
				R v;
	
				try {
					v = mapper.apply(t);
				} catch (Throwable e) {
					done = true;
					s.cancel();
					actual.onError(e);
					return;
				}
	
				if (v == null) {
					done = true;
					s.cancel();
					actual.onError(new NullPointerException("The mapper returned a null value."));
					return;
				}
	
				actual.onNext(v);
			} else
			if (m == 2) {
				actual.onNext(null);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Exceptions.onErrorDropped(t);
				return;
			}

			done = true;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			actual.onComplete();
		}

		@Override
		public boolean isStarted() {
			return s != null && !done;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public Object connectedInput() {
			return mapper;
		}

		@Override
		public Object connectedOutput() {
			return null;
		}

		@Override
		public Object upstream() {
			return s;
		}
		
		@Override
		public void request(long n) {
			s.request(n);
		}
		
		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public R poll() {
			// FIXME maybe should cache the result to avoid mapping twice in case of peek/poll pairs
			T v = s.poll();
			if (v != null) {
				return mapper.apply(v);
			}
			return null;
		}

		@Override
		public R peek() {
			// FIXME maybe should cache the result to avoid mapping twice in case of peek/poll pairs
			T v = s.peek();
			if (v != null) {
				return mapper.apply(v);
			}
			return null;
		}

		@Override
		public boolean isEmpty() {
			return s.isEmpty();
		}

		@Override
		public void clear() {
			s.clear();
		}

		@Override
		public boolean requestSyncFusion() {
			boolean b = s.requestSyncFusion();
			sourceMode = b ? SYNC : ASYNC;
			return b;
		}

		@Override
		public void drop() {
			s.drop();
		}
	}
}

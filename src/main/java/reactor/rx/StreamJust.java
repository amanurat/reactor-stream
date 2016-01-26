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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.graph.Connectable;
import reactor.core.state.Completable;
import reactor.core.util.Exceptions;
import reactor.fn.Supplier;

/**
 * A Stream that emits only one value and then complete.
 * <p>
 * Since the stream retains the value in a final field, any {@link this#subscribe(Subscriber)} will
 * replay the value. This is a "Cold" stream.
 * <p>
 * Create such stream with the provided factory, E.g.:
 * <pre>
 * {@code
 * Streams.just(1).consume(
 *    log::info,
 *    log::error,
 *    (-> log.info("complete"))
 * )
 * }
 * </pre>
 * Will log:
 * <pre>
 * {@code
 * 1
 * complete
 * }
 * </pre>
 *
 * @author Stephane Maldini
 */
final class StreamJust<T> extends Stream<T> implements Supplier<T>, Connectable {

	final public static StreamJust<?> EMPTY = new StreamJust<>(null);

	final T value;

	@SuppressWarnings("unchecked")
	public StreamJust(T value) {
		this.value = value;
	}

	@Override
	public T get() {
		return value;
	}

	@Override
	public void subscribe(final Subscriber<? super T> subscriber) {
		try {
			subscriber.onSubscribe(new SingleSubscription<>(value, subscriber));
		}
		catch (Throwable throwable) {
			Exceptions.throwIfFatal(throwable);
			subscriber.onError(throwable);
		}
	}

	@Override
	public Object connectedInput() {
		return null;
	}

	@Override
	public Object connectedOutput() {
		return value;
	}

	@Override
	public String toString() {
		return "singleValue=" + value;
	}

	private static final class SingleSubscription<T> implements Subscription, Completable {

		boolean terminado;
		final T                     value;
		final Subscriber<? super T> subscriber;

		public SingleSubscription(T value, Subscriber<? super T> subscriber) {
			this.value = value;
			this.subscriber = subscriber;
		}

		@Override
		public void request(long elements) {
			if (terminado) {
				return;
			}

			terminado = true;
			if (value != null) {
				subscriber.onNext(value);
			}
			subscriber.onComplete();
		}

		@Override
		public void cancel() {
			terminado = true;
		}

		@Override
		public boolean isStarted() {
			return !terminado;
		}

		@Override
		public boolean isTerminated() {
			return terminado;
		}

		@Override
		public Object upstream() {
			return value;
		}
	}
}

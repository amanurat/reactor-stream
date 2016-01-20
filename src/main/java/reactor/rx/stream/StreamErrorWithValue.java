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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.subscriber.SubscriberBarrier;
import reactor.core.util.Exceptions;
import reactor.fn.BiConsumer;

/**
 * @author Stephane Maldini
 * @since 2.0, 2.5
 */
final public class StreamErrorWithValue<T, E extends Throwable> extends StreamBarrier<T, T> {

	private final BiConsumer<Object, ? super E> consumer;
	private final Class<E>                      selector;

	public StreamErrorWithValue(Publisher<T> source, Class<E> selector, BiConsumer<Object, ? super E> consumer) {
		super(source);
		this.consumer = consumer;
		this.selector = selector;
	}

	@Override
	public Subscriber<? super T> apply(Subscriber<? super T> subscriber) {
		return new ErrorWithValueAction<>(subscriber, selector, consumer);
	}

	final static class ErrorWithValueAction<T, E extends Throwable> extends SubscriberBarrier<T, T> {

		private final BiConsumer<Object, ? super E> consumer;
		private final Class<E>                      selector;

		public ErrorWithValueAction(Subscriber<? super T> subscriber,
				Class<E> selector,
				BiConsumer<Object, ? super E> consumer) {
			super(subscriber);
			this.consumer = consumer;
			this.selector = selector;
		}

		@Override
		@SuppressWarnings("unchecked")
		protected void doError(Throwable cause) {
			if (selector.isAssignableFrom(cause.getClass())) {
				if (consumer != null) {
					consumer.accept(Exceptions.getFinalValueCause(cause), (E) cause);
				}
			}
			subscriber.onError(cause);
		}

		@Override
		public String toString() {
			return super.toString() + "{" +
					"catch-type=" + selector +
					'}';
		}
	}
}

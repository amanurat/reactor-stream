package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;
import reactor.fn.Supplier;

/**
 * Emits only the element at the given index position or signals a
 * default value if specified or IndexOutOfBoundsException if the sequence is shorter.
 *
 * @param <T> the value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoElementAt<T> extends reactor.core.publisher.MonoSource<T, T> {

	final long index;

	final Supplier<? extends T> defaultSupplier;

	public MonoElementAt(Publisher<? extends T> source, long index) {
		super(source);
		if (index < 0) {
			throw new IndexOutOfBoundsException("index >= required but it was " + index);
		}
		this.index = index;
		this.defaultSupplier = null;
	}

	public MonoElementAt(Publisher<? extends T> source, long index, Supplier<? extends T> defaultSupplier) {
		super(source);
		if (index < 0) {
			throw new IndexOutOfBoundsException("index >= required but it was " + index);
		}
		this.index = index;
		this.defaultSupplier = Objects.requireNonNull(defaultSupplier, "defaultSupplier");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new ElementAtSubscriber<>(s, index, defaultSupplier));
	}

	static final class ElementAtSubscriber<T>
			extends DeferredScalarSubscriber<T, T>
			implements Receiver {
		final Supplier<? extends T> defaultSupplier;

		long index;

		Subscription s;

		boolean done;

		public ElementAtSubscriber(Subscriber<? super T> actual, long index,
											Supplier<? extends T> defaultSupplier) {
			super(actual);
			this.index = index;
			this.defaultSupplier = defaultSupplier;
		}

		@Override
		public void request(long n) {
			super.request(n);
			if (n > 0L) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void cancel() {
			super.cancel();
			s.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				subscriber.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Exceptions.onNextDropped(t);
				return;
			}

			long i = index;
			if (i == 0) {
				done = true;
				s.cancel();

				subscriber.onNext(t);
				subscriber.onComplete();
				return;
			}
			index = i - 1;
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Exceptions.onErrorDropped(t);
				return;
			}
			done = true;

			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			Supplier<? extends T> ds = defaultSupplier;

			if (ds == null) {
				subscriber.onError(new IndexOutOfBoundsException());
			} else {
				T t;

				try {
					t = ds.get();
				} catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					subscriber.onError(Exceptions.unwrap(e));
					return;
				}

				if (t == null) {
					subscriber.onError(new NullPointerException("The defaultSupplier returned a null value"));
					return;
				}

				complete(t);
			}
		}


		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Object connectedInput() {
			return defaultSupplier;
		}
	}
}

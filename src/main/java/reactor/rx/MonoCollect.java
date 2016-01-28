package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.fn.BiConsumer;
import reactor.fn.Supplier;

/**
 * Collects the values of the source sequence into a container returned by
 * a supplier and a collector action working on the container and the current source
 * value.
 *
 * @param <T> the source value type
 * @param <R> the container value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoCollect<T, R> extends reactor.core.publisher.MonoSource<T, R> {

	final Supplier<R> supplier;

	final BiConsumer<? super R, ? super T> action;

	public MonoCollect(Publisher<? extends T> source, Supplier<R> supplier,
							BiConsumer<? super R, ? super T> action) {
		super(source);
		this.supplier = Objects.requireNonNull(supplier, "supplier");
		this.action = Objects.requireNonNull(action);
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {
		R container;

		try {
			container = supplier.get();
		} catch (Throwable e) {
			EmptySubscription.error(s, e);
			return;
		}

		if (container == null) {
			EmptySubscription.error(s, new NullPointerException("The supplier returned a null container"));
			return;
		}

		source.subscribe(new CollectSubscriber<>(s, action, container));
	}

	static final class CollectSubscriber<T, R>
			extends DeferredScalarSubscriber<T, R>
			implements Receiver {

		final BiConsumer<? super R, ? super T> action;

		Subscription s;

		boolean done;

		public CollectSubscriber(Subscriber<? super R> actual, BiConsumer<? super R, ? super T> action,
										  R container) {
			super(actual);
			this.action = action;
			this.value = container;
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

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Exceptions.onNextDropped(t);
				return;
			}

			try {
				action.accept(value, t);
			} catch (Throwable e) {
				cancel();
				Exceptions.throwIfFatal(e);
				onError(Exceptions.unwrap(e));
			}
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
			complete(value);
		}

		@Override
		public void setValue(R value) {
			// value is constant
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
			return action;
		}

		@Override
		public Object connectedOutput() {
			return value;
		}
	}
}

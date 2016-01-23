package reactor.rx.stream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.subscriber.SubscriberMultiSubscription;
import reactor.core.util.Exceptions;
import reactor.fn.Predicate;

/**
 * Repeatedly subscribes to the source if the predicate returns true after
 * completion of the previous subscription.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamRetryPredicate<T> extends StreamBarrier<T, T> {

	final Predicate<Throwable> predicate;

	public StreamRetryPredicate(Publisher<? extends T> source, Predicate<Throwable> predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {

		RetryPredicateSubscriber<T> parent = new RetryPredicateSubscriber<>(source, s, predicate);

		s.onSubscribe(parent);

		if (!parent.isCancelled()) {
			parent.resubscribe();
		}
	}

	static final class RetryPredicateSubscriber<T>
	  extends SubscriberMultiSubscription<T, T> {

		final Publisher<? extends T> source;

		final Predicate<Throwable> predicate;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<RetryPredicateSubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(RetryPredicateSubscriber.class, "wip");

		long produced;

		public RetryPredicateSubscriber(Publisher<? extends T> source, 
				Subscriber<? super T> actual, Predicate<Throwable> predicate) {
			super(actual);
			this.source = source;
			this.predicate = predicate;
		}

		@Override
		public void onNext(T t) {
			produced++;

			subscriber.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			boolean b;
			
			try {
				b = predicate.test(t);
			} catch (Throwable e) {
				Exceptions.throwIfFatal(e);
				Throwable _t = Exceptions.unwrap(e);
				_t.addSuppressed(t);
				subscriber.onError(_t);
				return;
			}
			
			if (b) {
				resubscribe();
			} else {
				subscriber.onError(t);
			}
		}
		
		@Override
		public void onComplete() {
			
			subscriber.onComplete();
		}

		void resubscribe() {
			if (WIP.getAndIncrement(this) == 0) {
				do {
					if (isCancelled()) {
						return;
					}

					long c = produced;
					if (c != 0L) {
						produced = 0L;
						produced(c);
					}

					source.subscribe(this);

				} while (WIP.decrementAndGet(this) != 0);
			}
		}
	}
}

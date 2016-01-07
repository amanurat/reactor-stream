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

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.SubscriberWithDemand;
import reactor.core.timer.Timer;
import reactor.fn.Consumer;
import reactor.rx.Stream;

/**
 * WindowAction is forwarding events on a steam until {@param backlog} is reached, after that streams collected events
 * further, complete it and create a fresh new stream.
 * @author Stephane Maldini
 * @since 2.0, 2.5
 */
public final class StreamWindowShift<T> extends StreamBarrier<T, Stream<T>> {

	private final int      skip;
	private final int      batchSize;
	private final long     timeshift;
	private final long     timespan;
	private final TimeUnit unit;
	private final Timer    timer;

	public StreamWindowShift(Publisher<T> source, Timer timer, int size, int skip) {
		this(source, size, skip, -1L, -1L, null, timer);
	}

	public StreamWindowShift(Publisher<T> source, int size,
			int skip,
			final long timespan,
			final long timeshift,
			TimeUnit unit,
			final Timer timer) {
		super(source);
		this.skip = skip;
		this.batchSize = size;
		if (timespan > 0 && timeshift > 0) {
			this.timeshift = timeshift;
			this.unit = unit != null ? unit : TimeUnit.SECONDS;
			this.timer = timer;
			this.timespan = timespan;
		}
		else {
			this.timeshift = -1L;
			this.timespan = -1L;
			this.unit = null;
			this.timer = null;
		}
	}

	@Override
	public Subscriber<? super T> apply(Subscriber<? super Stream<T>> subscriber) {
		return new WindowShiftAction<>(subscriber, batchSize, skip, timespan, timeshift, unit, timer);
	}

	static final class WindowShiftAction<T> extends SubscriberWithDemand<T, Stream<T>> {

		private final Consumer<Long> timeshiftTask;
		private final List<StreamWindow.Window<T>> currentWindows = new LinkedList<>();
		private final int      skip;
		private final int      batchSize;
		private final long     timeshift;
		private final TimeUnit unit;
		private final Timer    timer;
		private       int      index;
		private       Pausable timeshiftRegistration;

		public WindowShiftAction(Subscriber<? super Stream<T>> actual,
				int size,
				int skip,
				final long timespan,
				final long timeshift,
				TimeUnit unit,
				final Timer timer) {

			super(actual);

			this.skip = skip;
			this.batchSize = size;
			if (timespan > 0 && timeshift > 0) {
				final TimeUnit targetUnit = unit != null ? unit : TimeUnit.SECONDS;
				final Consumer<StreamWindow.Window<T>> flushTimerTask = new Consumer<StreamWindow.Window<T>>() {
					@Override
					public void accept(StreamWindow.Window<T> bucket) {
						Iterator<StreamWindow.Window<T>> it = currentWindows.iterator();
						while (it.hasNext()) {
							StreamWindow.Window<T> itBucket = it.next();
							if (bucket == itBucket) {
								it.remove();
								bucket.onComplete();
								break;
							}
						}
					}
				};

				this.timeshiftTask = new Consumer<Long>() {
					@Override
					public void accept(Long aLong) {
						if (isTerminated()) {
							return;
						}

						final StreamWindow.Window<T> bucket = createWindowStream();

						timer.submit(new Consumer<Long>() {
							@Override
							public void accept(Long aLong) {
								flushTimerTask.accept(bucket);
							}
						}, timespan, targetUnit);
					}
				};

				this.timeshift = timeshift;
				this.unit = targetUnit;
				this.timer = timer;
			}
			else {
				this.timeshift = -1L;
				this.unit = null;
				this.timer = null;
				this.timeshiftTask = null;
			}
		}

		@Override
		protected void doOnSubscribe(Subscription subscription) {
			subscriber.onSubscribe(this);
			if (timer != null) {
				timeshiftRegistration = timer.schedule(timeshiftTask, timeshift, unit);
			}
		}

		@Override
		protected void doNext(T value) {
			if (timer == null && index++ % skip == 0) {
				createWindowStream();
			}
			flushCallback(value);
		}

		@Override
		protected void checkedComplete() {
			for (StreamWindow.Window<T> bucket : currentWindows) {
				bucket.onComplete();
			}
			subscriber.onComplete();
		}

		private void flushCallback(T event) {
			Iterator<StreamWindow.Window<T>> it = currentWindows.iterator();
			while (it.hasNext()) {
				StreamWindow.Window<T> bucket = it.next();
				bucket.onNext(event);
				if (bucket.count == batchSize) {
					it.remove();
					bucket.onComplete();
				}
			}
		}

		protected StreamWindow.Window<T> createWindowStream() {
			StreamWindow.Window<T> action = new StreamWindow.Window<T>(timer);
			synchronized (currentWindows) {
				currentWindows.add(action);
			}

			subscriber.onNext(action);

			return action;
		}

		@Override
		protected void checkedError(Throwable ev) {
			for (StreamWindow.Window<T> bucket : currentWindows) {
				bucket.onError(ev);
			}
			subscriber.onError(ev);
		}

		@Override
		protected void doTerminate() {
			currentWindows.clear();
			if (timeshiftRegistration != null) {
				timeshiftRegistration.cancel();
			}
		}
	}

}

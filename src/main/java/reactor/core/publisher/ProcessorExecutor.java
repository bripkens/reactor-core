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
package reactor.core.publisher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.state.Cancellable;
import reactor.core.state.Completable;
import reactor.core.state.Failurable;
import reactor.core.state.Groupable;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.ExecutorUtils;

/**
 * A base processor used by executor backed processors to take care of their ExecutorService
 *
 * @author Stephane Maldini
 */
public abstract class ProcessorExecutor<IN, OUT> extends FluxProcessor<IN, OUT>
		implements Completable, Cancellable, Failurable, Groupable {

	protected final ExecutorService executor;

	volatile boolean cancelled;
	volatile int     terminated;
	volatile Throwable error;

	protected final ClassLoader contextClassLoader;
	protected final String name;
	protected final boolean autoCancel;

	@SuppressWarnings("unused")
	volatile       int                                                  subscriberCount  = 0;
	protected static final AtomicIntegerFieldUpdater<ProcessorExecutor> SUBSCRIBER_COUNT =
			AtomicIntegerFieldUpdater
					.newUpdater(ProcessorExecutor.class, "subscriberCount");

	protected final static AtomicIntegerFieldUpdater<ProcessorExecutor> TERMINATED =
			AtomicIntegerFieldUpdater.newUpdater(ProcessorExecutor.class, "terminated");

	protected ProcessorExecutor(String name, ExecutorService executor,
			boolean autoCancel) {
		this.autoCancel = autoCancel;
		contextClassLoader = new ClassLoader(Thread.currentThread()
		                                           .getContextClassLoader()) {
		};
		this.name = null != name ? name : getClass().getSimpleName();
		if (executor == null) {
			this.executor = ExecutorUtils.singleUse(name, contextClassLoader);
		}
		else {
			this.executor = executor;
		}
	}

	@Override
	public int getMode() {
		return UNIQUE;
	}

	@Override
	public Object key() {
		return contextClassLoader.hashCode();
	}

	@Override
	public String getName() {
		return "/Processors/"+name;
	}

	/**
	 * @return a snapshot number of available onNext before starving the resource
	 */
	public long getAvailableCapacity() {
		return getCapacity();
	}

	protected boolean incrementSubscribers() {
		return SUBSCRIBER_COUNT.getAndIncrement(this) == 0;
	}

	@Override
	protected void doOnSubscribe(Subscription s) {
		if (s != EmptySubscription.INSTANCE) {
			requestTask(s);
		}
	}

	protected void requestTask(final Subscription s) {
		//implementation might run a specific request task for the given subscription
	}

	protected void doComplete() {

	}

	@Override
	public final void onComplete() {
		if (TERMINATED.compareAndSet(this, 0, 1)) {
			upstreamSubscription = null;
			ExecutorUtils.shutdownIfSingleUse(executor);
			doComplete();
		}
	}

	protected void doError(Throwable throwable) {

	}

	@Override
	public final void onError(Throwable t) {
		super.onError(t);
		if (TERMINATED.compareAndSet(this, 0, 1)) {
			error = t;
			upstreamSubscription = null;
			ExecutorUtils.shutdownIfSingleUse(executor);
			doError(t);
		}
	}


	/**
	 * Block until all submitted tasks have completed, then do a normal {@link #shutdown()}.
	 */
	public boolean awaitAndShutdown() {
		return awaitAndShutdown(-1, TimeUnit.SECONDS);
	}

	/**
	 * Block until all submitted tasks have completed, then do a normal {@link #shutdown()}.
	 */
	public boolean awaitAndShutdown(long timeout, TimeUnit timeUnit) {
		try {
			shutdown();
			return executor.awaitTermination(timeout, timeUnit);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@Override
	protected void cancel(Subscription subscription) {
		cancelled = true;
		if(TERMINATED.compareAndSet(this, 0, 1)) {
			ExecutorUtils.shutdownIfSingleUse(executor);
		}
	}

	/**
	 * Shutdown this {@code Processor}, forcibly halting any work currently executing and discarding any tasks that
	 * have not yet been executed.
	 */
	public void forceShutdown() {
		if (executor.isShutdown()) return;
		executor.shutdownNow();
	}


	/**
	 * Determine whether this {@code Processor} can be used.
	 *
	 * @return {@literal true} if this {@code Resource} is alive and can be used, {@literal false} otherwise.
	 */
	public boolean alive() {
		return 0 == terminated;
	}

	/**
	 * Shutdown this active {@code Processor} such that it can no longer be used. If the resource carries any work,
	 * it will wait (but NOT blocking the caller) for all the remaining tasks to perform before closing the resource.
	 */
	public void shutdown() {
		try {
			onComplete();
			executor.shutdown();
		} catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			onError(t);
		}
	}

	/**
	 * @return true if the attached Subscribers will read exclusive sequences (akin to work-queue pattern)
	 */
	public abstract boolean isWork();

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public boolean isStarted() {
		return upstreamSubscription != null;
	}

	@Override
	public boolean isTerminated() {
		return terminated == 1;
	}

	/**
	 * @return true if the classLoader marker is detected in the current thread
	 */
	public final boolean isInContext() {
		return Thread.currentThread().getContextClassLoader() == contextClassLoader;
	}

	protected final boolean startSubscriber(Subscriber<? super OUT> subscriber, Subscription subscription){
		try {
			Thread.currentThread()
			      .setContextClassLoader(contextClassLoader);
			subscriber.onSubscribe(subscription);
			return true;
		}
		catch (Throwable t) {
			EmptySubscription.error(subscriber, t);
			return false;
		}
	}

	protected int decrementSubscribers() {
		Subscription subscription = upstreamSubscription;
		int subs = SUBSCRIBER_COUNT.decrementAndGet(this);
		if (subs == 0) {
			if (subscription != null && autoCancel) {
				upstreamSubscription = null;
				cancel(subscription);
			}
			return subs;
		}
		return subs;
	}

	@Override
	public final Throwable getError() {
		return error;
	}
}

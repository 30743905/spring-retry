/*
 * Copyright 2006-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.retry.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.retry.ExhaustedRetryException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryException;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryOperations;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.RetryState;
import org.springframework.retry.TerminatedRetryException;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.CircuitBreakerRetryPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.MapRetryContextCache;
import org.springframework.retry.policy.RetryContextCache;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Template class that simplifies the execution of operations with retry semantics.
 * <p>
 * Retryable operations are encapsulated in implementations of the {@link RetryCallback}
 * interface and are executed using one of the supplied execute methods.
 * <p>
 * By default, an operation is retried if is throws any {@link Exception} or subclass of
 * {@link Exception}. This behaviour can be changed by using the
 * {@link #setRetryPolicy(RetryPolicy)} method.
 * <p>
 * Also by default, each operation is retried for a maximum of three attempts with no back
 * off in between. This behaviour can be configured using the
 * {@link #setRetryPolicy(RetryPolicy)} and {@link #setBackOffPolicy(BackOffPolicy)}
 * properties. The {@link org.springframework.retry.backoff.BackOffPolicy} controls how
 * long the pause is between each individual retry attempt.
 * <p>
 * A new instance can be fluently configured via {@link #builder}, e.g: <pre> {@code
 * RetryTemplate.builder()
 *                 .maxAttempts(10)
 *                 .fixedBackoff(1000)
 *                 .build();
 * }</pre> See {@link RetryTemplateBuilder} for more examples and details.
 * <p>
 * This class is thread-safe and suitable for concurrent access when executing operations
 * and when performing configuration changes. As such, it is possible to change the number
 * of retries on the fly, as well as the {@link BackOffPolicy} used and no in progress
 * retryable operations will be affected.
 *
 * @author Rob Harrop
 * @author Dave Syer
 * @author Gary Russell
 * @author Artem Bilan
 * @author Josh Long
 * @author Aleksandr Shamukov
 */
public class RetryTemplate implements RetryOperations {

	/**
	 * Retry context attribute name that indicates the context should be considered global
	 * state (never closed). TODO: convert this to a flag in the RetryState.
	 */
	private static final String GLOBAL_STATE = "state.global";

	protected final Log logger = LogFactory.getLog(getClass());

	private volatile BackOffPolicy backOffPolicy = new NoBackOffPolicy();

	private volatile RetryPolicy retryPolicy = new SimpleRetryPolicy(3);

	private volatile RetryListener[] listeners = new RetryListener[0];

	private RetryContextCache retryContextCache = new MapRetryContextCache();

	private boolean throwLastExceptionOnExhausted;

	/**
	 * Main entry point to configure RetryTemplate using fluent API. See
	 * {@link RetryTemplateBuilder} for usage examples and details.
	 * @return a new instance of RetryTemplateBuilder with preset default behaviour, that
	 * can be overwritten during manual configuration
	 * @since 1.3
	 */
	public static RetryTemplateBuilder builder() {
		return new RetryTemplateBuilder();
	}

	/**
	 * Creates a new default instance. The properties of default instance are described in
	 * {@link RetryTemplateBuilder} documentation.
	 * @return a new instance of RetryTemplate with default behaviour
	 * @since 1.3
	 */
	public static RetryTemplate defaultInstance() {
		return new RetryTemplateBuilder().build();
	}

	/**
	 * @param throwLastExceptionOnExhausted the throwLastExceptionOnExhausted to set
	 */
	public void setThrowLastExceptionOnExhausted(boolean throwLastExceptionOnExhausted) {
		this.throwLastExceptionOnExhausted = throwLastExceptionOnExhausted;
	}

	/**
	 * Public setter for the {@link RetryContextCache}.
	 * @param retryContextCache the {@link RetryContextCache} to set.
	 */
	public void setRetryContextCache(RetryContextCache retryContextCache) {
		this.retryContextCache = retryContextCache;
	}

	/**
	 * Setter for listeners. The listeners are executed before and after a retry block
	 * (i.e. before and after all the attempts), and on an error (every attempt).
	 * @param listeners the {@link RetryListener}s
	 * @see RetryListener
	 */
	public void setListeners(RetryListener[] listeners) {
		this.listeners = Arrays.asList(listeners).toArray(new RetryListener[listeners.length]);
	}

	/**
	 * Register an additional listener at the end of the list.
	 * @param listener the {@link RetryListener}
	 * @see #setListeners(RetryListener[])
	 */
	public void registerListener(RetryListener listener) {
		registerListener(listener, this.listeners.length);
	}

	/**
	 * Register an additional listener at the specified index.
	 * @param listener the {@link RetryListener}
	 * @param index the position in the list.
	 * @since 1.3
	 * @see #setListeners(RetryListener[])
	 */
	public void registerListener(RetryListener listener, int index) {
		List<RetryListener> list = new ArrayList<RetryListener>(Arrays.asList(this.listeners));
		if (index >= list.size()) {
			list.add(listener);
		}
		else {
			list.add(index, listener);
		}
		this.listeners = list.toArray(new RetryListener[list.size()]);
	}

	/**
	 * Return true if at least one listener is registered.
	 * @return true if listeners present.
	 * @since 1.3
	 */
	public boolean hasListeners() {
		return this.listeners.length > 0;
	}

	/**
	 * Setter for {@link BackOffPolicy}.
	 * @param backOffPolicy the {@link BackOffPolicy}
	 */
	public void setBackOffPolicy(BackOffPolicy backOffPolicy) {
		this.backOffPolicy = backOffPolicy;
	}

	/**
	 * Setter for {@link RetryPolicy}.
	 * @param retryPolicy the {@link RetryPolicy}
	 */
	public void setRetryPolicy(RetryPolicy retryPolicy) {
		this.retryPolicy = retryPolicy;
	}

	/**
	 * Keep executing the callback until it either succeeds or the policy dictates that we
	 * stop, in which case the most recent exception thrown by the callback will be
	 * rethrown.
	 *
	 * @see RetryOperations#execute(RetryCallback)
	 * @param retryCallback the {@link RetryCallback}
	 * @throws TerminatedRetryException if the retry has been manually terminated by a
	 * listener.
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback) throws E {
		return doExecute(retryCallback, null, null);
	}

	/**
	 * Keep executing the callback until it either succeeds or the policy dictates that we
	 * stop, in which case the recovery callback will be executed.
	 *
	 * @see RetryOperations#execute(RetryCallback, RecoveryCallback)
	 * @param retryCallback the {@link RetryCallback}
	 * @param recoveryCallback the {@link RecoveryCallback}
	 * @throws TerminatedRetryException if the retry has been manually terminated by a
	 * listener.
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback,
			RecoveryCallback<T> recoveryCallback) throws E {
		return doExecute(retryCallback, recoveryCallback, null);
	}

	/**
	 * Execute the callback once if the policy dictates that we can, re-throwing any
	 * exception encountered so that clients can re-present the same task later.
	 *
	 * @see RetryOperations#execute(RetryCallback, RetryState)
	 * @param retryCallback the {@link RetryCallback}
	 * @param retryState the {@link RetryState}
	 * @throws ExhaustedRetryException if the retry has been exhausted.
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback, RetryState retryState)
			throws E, ExhaustedRetryException {
		return doExecute(retryCallback, null, retryState);
	}

	/**
	 * Execute the callback once if the policy dictates that we can, re-throwing any
	 * exception encountered so that clients can re-present the same task later.
	 *
	 * @see RetryOperations#execute(RetryCallback, RetryState)
	 * @param retryCallback the {@link RetryCallback}
	 * @param recoveryCallback the {@link RecoveryCallback}
	 * @param retryState the {@link RetryState}
	 */
	@Override
	public final <T, E extends Throwable> T execute(RetryCallback<T, E> retryCallback,
			RecoveryCallback<T> recoveryCallback, RetryState retryState) throws E, ExhaustedRetryException {
		return doExecute(retryCallback, recoveryCallback, retryState);
	}

	/**
	 * Execute the callback once if the policy dictates that we can, otherwise execute the
	 * recovery callback.
	 * @param recoveryCallback the {@link RecoveryCallback}
	 * @param retryCallback the {@link RetryCallback}
	 * @param state the {@link RetryState}
	 * @param <T> the type of the return value
	 * @param <E> the exception type to throw
	 * @see RetryOperations#execute(RetryCallback, RecoveryCallback, RetryState)
	 * @throws ExhaustedRetryException if the retry has been exhausted.
	 * @throws E an exception if the retry operation fails
	 * @return T the retried value
	 */
	protected <T, E extends Throwable> T doExecute(RetryCallback<T, E> retryCallback,
			RecoveryCallback<T> recoveryCallback, RetryState state) throws E, ExhaustedRetryException {

		//重试策略
		RetryPolicy retryPolicy = this.retryPolicy;
		//重试回避策略
		BackOffPolicy backOffPolicy = this.backOffPolicy;

		// Allow the retry policy to initialise itself...
		/**
		 * 创建RetryContext
		 * 1、如果是无状态，则调用RetryPolicy.open方法创建，一般是new创建一个新的context
		 * 2、如果是有状态，这里分为两种情况：
		 * 		a、isForceRefresh=true(强制刷新创建)：则也是调用RetryPolicy.open方法创建，
		 * 			然后将state设置到context中，并将context缓存到retryContextCache中
		 * 		b、isForceRefresh=false(使用缓存)：优先从retryContextCache缓存中获取context，没有则进行【步骤2】创建context
		 *
		 */
		RetryContext context = open(retryPolicy, state);
		CompositeRetryPolicy.CompositeRetryContext compositeRetryContext = (CompositeRetryPolicy.CompositeRetryContext) context;
		RetryContext[] cts = compositeRetryContext.contexts;
		for(RetryContext ct:cts){
			if(ct instanceof CircuitBreakerRetryPolicy.CircuitBreakerRetryContext){
				logger.info("--------> open context:"+System.identityHashCode(context)+"-->"
						+System.identityHashCode(ct)+",isOpen:"+((CircuitBreakerRetryPolicy.CircuitBreakerRetryContext)ct).isOpen());
			}

		}

		if (this.logger.isTraceEnabled()) {
			this.logger.trace("RetryContext retrieved: " + context);
		}

		// Make sure the context is available globally for clients who need
		// it...
		/**
		 * 内部通过ThreadLocal保存context
		 */
		RetrySynchronizationManager.register(context);

		Throwable lastException = null;

		boolean exhausted = false;
		try {

			// Give clients a chance to enhance the context...
			/**
			 * 调用RetryListener#open，第一次执行调用，如果有返回false则抛出TerminatedRetryException异常
			 */
			boolean running = doOpenInterceptors(retryCallback, context);

			if (!running) {
				throw new TerminatedRetryException("Retry terminated abnormally by interceptor before first attempt");
			}

			// Get or Start the backoff context...
			BackOffContext backOffContext = null;
			Object resource = context.getAttribute("backOffContext");

			if (resource instanceof BackOffContext) {
				backOffContext = (BackOffContext) resource;
			}

			if (backOffContext == null) {
				//创建BackOffContext并放入到context中
				backOffContext = backOffPolicy.start(context);
				if (backOffContext != null) {
					context.setAttribute("backOffContext", backOffContext);
				}
			}

			/*
			 * We allow the whole loop to be skipped if the policy or context already
			 * forbid the first try. This is used in the case of external retry to allow a
			 * recovery in handleRetryExhausted without the callback processing (which
			 * would throw an exception).
			 */
			while (canRetry(retryPolicy, context) && !context.isExhaustedOnly()) {

				try {
					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Retry: count=" + context.getRetryCount());
					}
					// Reset the last exception, so if we are successful
					// the close interceptors will not think we failed...
					lastException = null;
					//调用RetryCallback#doWithRetry，该方法包装用户业务逻辑
					return retryCallback.doWithRetry(context);
				}
				catch (Throwable e) {
					//保存抛出异常
					lastException = e;

					try {
						//将异常注册到RetryContext中,RetryContext#getLastThrowable可以获取到该异常
						registerThrowable(retryPolicy, state, context, e);
					}
					catch (Exception ex) {
						throw new TerminatedRetryException("Could not register throwable", ex);
					}
					finally {
						//调用RetryListener#onError方法
						doOnErrorInterceptors(retryCallback, context, e);
					}

					if (canRetry(retryPolicy, context) && !context.isExhaustedOnly()) {
						try {
							//如果需要重试，则进行回避策略，即Thread.sleep休眠一定时间后再进行重试
							backOffPolicy.backOff(backOffContext);
						}
						catch (BackOffInterruptedException ex) {
							lastException = e;
							// back off was prevented by another thread - fail the retry
							if (this.logger.isDebugEnabled()) {
								this.logger.debug("Abort retry because interrupted: count=" + context.getRetryCount());
							}
							throw ex;
						}
					}

					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Checking for rethrow: count=" + context.getRetryCount());
					}

					/**
					 * shouldRethrow：确认是否需要重新抛出(对于有事务的逻辑，重新抛出指定异常方便事务回滚)
					 * 返回true条件：
					 * 	1、有状态重试
					 * 	2、state.rollbackFor(context.getLastThrowable() == true
					 *
					 * 	注意：shouldRethrow=true时，是不会执行handleRetryExhausted()逻辑，即不会执行RecoveryCallback
					 */
					if (shouldRethrow(retryPolicy, context, state)) {
						if (this.logger.isDebugEnabled()) {
							this.logger.debug("Rethrow in retry for policy: count=" + context.getRetryCount());
						}
						throw RetryTemplate.<E>wrapIfNecessary(e);
					}

				}

				/*
				 * A stateful attempt that can retry may rethrow the exception before now,
				 * but if we get this far in a stateful retry there's a reason for it,
				 * like a circuit breaker or a rollback classifier.
				 *
				 * 如果是有状态重试，且有GLOBAL_STATE属性，则立即跳出重试终止；
				 * 当抛出的异常是非需要执行回滚操作的异常时，才会执行到此处，CircuitBreakerRetryPolicy会在此跳出循环
				 */
				if (state != null && context.hasAttribute(GLOBAL_STATE)) {
					break;
				}
			}

			if (state == null && this.logger.isDebugEnabled()) {
				this.logger.debug("Retry failed last attempt: count=" + context.getRetryCount());
			}

			exhausted = true;
			/**
			 * handleRetryExhausted：处理重试结束逻辑
			 * 1、将context从缓存移除：只有有状态context才会被缓存，有状态移除缓存分为下面两种情况
			 * 		有状态 && context中没有state.global属性：则移除
			 * 		有状态 && context中有state.global属性：不需要移除
			 * 2、返回结果处理，分为三种情况：
			 * 		a、recoveryCallback != null，回调recoveryCallback作为返回
			 * 		b、recoveryCallback == null，但是有状态(state != null)：
			 * 			a1、retryTemplate.throwLastExceptionOnExhausted == true: 抛出lastThrowable异常
			 * 			a2、retryTemplate.throwLastExceptionOnExhausted == false(默认): 将lastThrowable包装成ExhaustedRetryException抛出
			 * 		c、无状态(state == null 且 recoveryCallback == null)，将lastThrowable抛出
			 */
			return handleRetryExhausted(recoveryCallback, context, state);

		}
		catch (Throwable e) {
			throw RetryTemplate.<E>wrapIfNecessary(e);
		}
		finally {
			/**
			 * 1、close：调用RetryPolicy.close()，一般是空操作；将context的属性context.closed设置成true
			 * 			有状态 && 重试成功 && context中没有state.global属性：retryContextCache缓存移除context
			 * 2、doCloseInterceptors:回调RetryListener#close()方法
			 * 3、将之前设置到ThreadLocal中的context移除
			 */
			System.out.println("*********>"+lastException+",exhausted:"+exhausted);
			close(retryPolicy, context, state, lastException == null || exhausted);
			doCloseInterceptors(retryCallback, context, lastException);
			RetrySynchronizationManager.clear();
		}

	}

	/**
	 * Decide whether to proceed with the ongoing retry attempt. This method is called
	 * before the {@link RetryCallback} is executed, but after the backoff and open
	 * interceptors.
	 * @param retryPolicy the policy to apply
	 * @param context the current retry context
	 * @return true if we can continue with the attempt
	 */
	protected boolean canRetry(RetryPolicy retryPolicy, RetryContext context) {
		return retryPolicy.canRetry(context);
	}

	/**
	 * Clean up the cache if necessary and close the context provided (if the flag
	 * indicates that processing was successful).
	 * @param retryPolicy the {@link RetryPolicy}
	 * @param context the {@link RetryContext}
	 * @param state the {@link RetryState}
	 * @param succeeded whether the close succeeded
	 */
	protected void close(RetryPolicy retryPolicy, RetryContext context, RetryState state, boolean succeeded) {
		if (state != null) {
			if (succeeded) {
				//有状态 && 重试成功 && context中没有state.global属性，context从retryContextCache移除
				if (!context.hasAttribute(GLOBAL_STATE)) {
					logger.info("--------> succeeded, remove context");
					this.retryContextCache.remove(state.getKey());
				}
				retryPolicy.close(context);
				context.setAttribute(RetryContext.CLOSED, true);
			}
		}
		else {
			retryPolicy.close(context);
			context.setAttribute(RetryContext.CLOSED, true);
		}
	}

	protected void registerThrowable(RetryPolicy retryPolicy, RetryState state, RetryContext context, Throwable e) {
		retryPolicy.registerThrowable(context, e);
		registerContext(context, state);
	}

	private void registerContext(RetryContext context, RetryState state) {
		if (state != null) {
			Object key = state.getKey();
			if (key != null) {
				if (context.getRetryCount() > 1 && !this.retryContextCache.containsKey(key)) {
					throw new RetryException("Inconsistent state for failed item key: cache key has changed. "
							+ "Consider whether equals() or hashCode() for the key might be inconsistent, "
							+ "or if you need to supply a better key");
				}
				this.retryContextCache.put(key, context);
			}
		}
	}

	/**
	 * Delegate to the {@link RetryPolicy} having checked in the cache for an existing
	 * value if the state is not null.
	 * @param state a {@link RetryState}
	 * @param retryPolicy a {@link RetryPolicy} to delegate the context creation
	 * @return a retry context, either a new one or the one used last time the same state
	 * was encountered
	 */
	protected RetryContext open(RetryPolicy retryPolicy, RetryState state) {

		if (state == null) {
			return doOpenInternal(retryPolicy);
		}

		Object key = state.getKey();
		if (state.isForceRefresh()) {
			return doOpenInternal(retryPolicy, state);
		}

		// If there is no cache hit we can avoid the possible expense of the
		// cache re-hydration.
		if (!this.retryContextCache.containsKey(key)) {
			// The cache is only used if there is a failure.
			return doOpenInternal(retryPolicy, state);
		}

		RetryContext context = this.retryContextCache.get(key);
		if (context == null) {
			if (this.retryContextCache.containsKey(key)) {
				throw new RetryException("Inconsistent state for failed item: no history found. "
						+ "Consider whether equals() or hashCode() for the item might be inconsistent, "
						+ "or if you need to supply a better ItemKeyGenerator");
			}
			// The cache could have been expired in between calls to
			// containsKey(), so we have to live with this:
			return doOpenInternal(retryPolicy, state);
		}

		// Start with a clean slate for state that others may be inspecting
		context.removeAttribute(RetryContext.CLOSED);
		context.removeAttribute(RetryContext.EXHAUSTED);
		context.removeAttribute(RetryContext.RECOVERED);
		return context;

	}

	private RetryContext doOpenInternal(RetryPolicy retryPolicy, RetryState state) {
		/**
		 * doOpenInternal方法是调用的policy的open方法创建RetryContext
		 * 比如：SimpleRetryPolicy#open()方法会 new SimpleRetryContext(parent)
		 * 另外在创建的 时候传入了一个父类 context:RetrySynchronizationManager.getContext()
		 *
		 * 那么RetrySynchronizationManager 是在什么时候注册的呢？
		 * 实在从上面的代码看到，是在 open 方法结束之后，注册的，也就是说，如果同一个线程可如果多次重试，那么 context 是会保留的下来的，是一个链式结构。
		 * 在多个Context 之间可以共享 state 而这个 state 可以放到外边，多个template 可以共享一个state
		 * 当我们的 state 状态设置成 不强制刷新，则会从 this.retryContextCache 中去寻找相关key对应的context用来复用，这样一来就实现了 多个context 共享一个 state.
		 */
		RetryContext context = retryPolicy.open(RetrySynchronizationManager.getContext());
		if (state != null) {
			context.setAttribute(RetryContext.STATE_KEY, state.getKey());
		}
		if (context.hasAttribute(GLOBAL_STATE)) {
			registerContext(context, state);
		}
		return context;
	}

	private RetryContext doOpenInternal(RetryPolicy retryPolicy) {
		return doOpenInternal(retryPolicy, null);
	}

	/**
	 * Actions to take after final attempt has failed. If there is state clean up the
	 * cache. If there is a recovery callback, execute that and return its result.
	 * Otherwise throw an exception.
	 * @param recoveryCallback the callback for recovery (might be null)
	 * @param context the current retry context
	 * @param state the {@link RetryState}
	 * @param <T> the type to classify
	 * @throws Exception if the callback does, and if there is no callback and the state
	 * is null then the last exception from the context
	 * @throws ExhaustedRetryException if the state is not null and there is no recovery
	 * callback
	 * @return T the payload to return
	 * @throws Throwable if there is an error
	 */
	protected <T> T handleRetryExhausted(RecoveryCallback<T> recoveryCallback, RetryContext context, RetryState state)
			throws Throwable {
		context.setAttribute(RetryContext.EXHAUSTED, true);

		/**
		 * 如果有状态，但是context中没有state.global属性，从缓存中移除context
		 */
		if (state != null && !context.hasAttribute(GLOBAL_STATE)) {
			this.retryContextCache.remove(state.getKey());
		}

		//recoveryCallback != null，回调
		if (recoveryCallback != null) {
			T recovered = recoveryCallback.recover(context);
			context.setAttribute(RetryContext.RECOVERED, true);
			return recovered;
		}

		//recoveryCallback == null，但是有状态(state != null)
		/**
		 * 如果recoveryCallback == null，则分为两种情况：
		 * 	1、有状态：
		 * 		throwLastExceptionOnExhausted=true：抛出最后异常
		 * 		throwLastExceptionOnExhausted=false：则将最后异常包装成ExhaustedRetryException抛出
		 *  2、无状态：抛出最后异常
		 */
		if (state != null) {
			this.logger.debug("Retry exhausted after last attempt with no recovery path.");
			rethrow(context, "Retry exhausted after last attempt with no recovery path");
		}

		//无状态(state == null 且 recoveryCallback == null)，将lastThrowable抛出
		throw wrapIfNecessary(context.getLastThrowable());
	}

	protected <E extends Throwable> void rethrow(RetryContext context, String message) throws E {
		if (this.throwLastExceptionOnExhausted) {
			@SuppressWarnings("unchecked")
			E rethrow = (E) context.getLastThrowable();
			throw rethrow;
		}
		else {
			throw new ExhaustedRetryException(message, context.getLastThrowable());
		}
	}

	/**
	 * Extension point for subclasses to decide on behaviour after catching an exception
	 * in a {@link RetryCallback}. Normal stateless behaviour is not to rethrow, and if
	 * there is state we rethrow.
	 * @param retryPolicy the retry policy
	 * @param context the current context
	 * @param state the current retryState
	 * @return true if the state is not null but subclasses might choose otherwise
	 */
	protected boolean shouldRethrow(RetryPolicy retryPolicy, RetryContext context, RetryState state) {
		return state != null && state.rollbackFor(context.getLastThrowable());
	}

	private <T, E extends Throwable> boolean doOpenInterceptors(RetryCallback<T, E> callback, RetryContext context) {

		boolean result = true;

		for (RetryListener listener : this.listeners) {
			result = result && listener.open(context, callback);
		}

		return result;

	}

	private <T, E extends Throwable> void doCloseInterceptors(RetryCallback<T, E> callback, RetryContext context,
			Throwable lastException) {
		for (int i = this.listeners.length; i-- > 0;) {
			this.listeners[i].close(context, callback, lastException);
		}
	}

	private <T, E extends Throwable> void doOnErrorInterceptors(RetryCallback<T, E> callback, RetryContext context,
			Throwable throwable) {
		for (int i = this.listeners.length; i-- > 0;) {
			this.listeners[i].onError(context, callback, throwable);
		}
	}

	/**
	 * Re-throws the original throwable if it is an Exception, and wraps non-exceptions
	 * into {@link RetryException}.
	 */
	private static <E extends Throwable> E wrapIfNecessary(Throwable throwable) throws RetryException {
		if (throwable instanceof Error) {
			throw (Error) throwable;
		}
		else if (throwable instanceof Exception) {
			@SuppressWarnings("unchecked")
			E rethrow = (E) throwable;
			return rethrow;
		}
		else {
			throw new RetryException("Exception in retry", throwable);
		}
	}

}

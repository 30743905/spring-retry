/*
 * Copyright 2015 the original author or authors.
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

package org.springframework.retry.policy;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.context.RetryContextSupport;

/**
 * @author Dave Syer
 *
 * delegate：真正执行的重试策略，由构造方法传入，当重试失败时，则执行熔断策略，默认SimpleRetryPolicy策略
 * openTimeout：openWindow，熔断器电路打开的超时时间，当超过openTimeout之后熔断器电路变成半打开状态（主要有一次重试成功，则闭合电路），默认5000毫秒
 * resetTimeout：timeout，重置熔断器重新闭合的超时时间。默认20000毫秒
 *
 * 当重试失败，且在熔断器打开时间窗口[0,openWindow) 内，立即熔断
 * 当重试失败，且超过timeout，熔断器电路重新闭合
 * 在熔断器半打开状态[openWindow, timeout] 时，只要重试成功则重置上下文，断路器闭合
 *
 */
@SuppressWarnings("serial")
public class CircuitBreakerRetryPolicy implements RetryPolicy {

	public static final String CIRCUIT_OPEN = "circuit.open";

	public static final String CIRCUIT_SHORT_COUNT = "circuit.shortCount";

	private static Log logger = LogFactory.getLog(CircuitBreakerRetryPolicy.class);

	private final RetryPolicy delegate;

	private long resetTimeout = 20000;

	private long openTimeout = 5000;

	public CircuitBreakerRetryPolicy() {
		this(new SimpleRetryPolicy());
	}

	public CircuitBreakerRetryPolicy(RetryPolicy delegate) {
		this.delegate = delegate;
	}

	/**
	 * Timeout for resetting circuit in milliseconds. After the circuit opens it will
	 * re-close after this time has elapsed and the context will be restarted.
	 * @param timeout the timeout to set in milliseconds
	 */
	public void setResetTimeout(long timeout) {
		this.resetTimeout = timeout;
	}

	/**
	 * Timeout for tripping the open circuit. If the delegate policy cannot retry and the
	 * time elapsed since the context was started is less than this window, then the
	 * circuit is opened.
	 * @param timeout the timeout to set in milliseconds
	 */
	public void setOpenTimeout(long timeout) {
		this.openTimeout = timeout;
	}

	@Override
	public boolean canRetry(RetryContext context) {
		CircuitBreakerRetryContext circuit = (CircuitBreakerRetryContext) context;
		if (circuit.isOpen()) {
			System.out.println("open::::::::::");
			circuit.incrementShortCircuitCount();
			return false;
		}
		else {
			System.out.println("reset::::::::::");
			circuit.reset();
		}
		boolean ret = this.delegate.canRetry(circuit.context);
		logger.info("CircuitBreaker ret:" + ret);


		return ret;
	}

	@Override
	public RetryContext open(RetryContext parent) {
		return new CircuitBreakerRetryContext(parent, this.delegate, this.resetTimeout, this.openTimeout);
	}

	@Override
	public void close(RetryContext context) {
		CircuitBreakerRetryContext circuit = (CircuitBreakerRetryContext) context;
		this.delegate.close(circuit.context);
	}

	@Override
	public void registerThrowable(RetryContext context, Throwable throwable) {
		CircuitBreakerRetryContext circuit = (CircuitBreakerRetryContext) context;
		circuit.registerThrowable(throwable);
		this.delegate.registerThrowable(circuit.context, throwable);
	}

	static class CircuitBreakerRetryContext extends RetryContextSupport {

		private volatile RetryContext context;

		private final RetryPolicy policy;

		private volatile long start = System.currentTimeMillis();

		private final long timeout;

		private final long openWindow;

		private final AtomicInteger shortCircuitCount = new AtomicInteger();

		public CircuitBreakerRetryContext(RetryContext parent, RetryPolicy policy, long timeout, long openWindow) {
			super(parent);
			this.policy = policy;
			this.timeout = timeout;
			this.openWindow = openWindow;
			this.context = createDelegateContext(policy, parent);
			setAttribute("state.global", true);
		}

		public void reset() {
			shortCircuitCount.set(0);
			setAttribute(CIRCUIT_SHORT_COUNT, shortCircuitCount.get());
		}

		public void incrementShortCircuitCount() {
			shortCircuitCount.incrementAndGet();
			setAttribute(CIRCUIT_SHORT_COUNT, shortCircuitCount.get());
		}

		private RetryContext createDelegateContext(RetryPolicy policy, RetryContext parent) {
			RetryContext context = policy.open(parent);
			reset();
			return context;
		}

		/**
		 * 比如：openTimeout=5000,resetTimeout=10000
		 * 理解：熔断是：5s内失败10次，那么开启熔断（熔断的恢复时间时10s）。
		 *
		 * 大于等于10次时：
		 * 		1.1 时间大于10s，那么关闭熔断器并重置失败次数和计时时间；
		 * 		1.2 时间小于5s，那么开启熔断器快速失败，并重置计时时间；
		 * 		1.3 时间[5,10]内，开启熔断器快速失败；
		 *
		 * 小于10次时：
		 * 		2.1 时间大于5s，那么重置失败次数和计时时间；
		 * 		2.2 时间小于5s，继续执行业务逻辑（不做处理）；
		 *
		 * 若开启了熔断后，请求会快速失败，若是1.2情况，那么后续的请求间隔时间必须大于5s，否则的话，每次请求进入均重置startTime，
		 * 若两次请求间隔小于openTimeout（A请求将当前时间设置为startTime，B请求立刻进入System.currentTimeMillis() - this.start依旧会小于this.openWindow），
		 * 那么每次都会进入else if (time < this.openWindow)判断，直接进行熔断。
		 *
		 * 注：10次是SimpleRetryPolicy配置的失败次数；5s是openTimeout时间；10s是resetTimeout时间。
		 *
		 *
		 *
		 * @return
		 */
		public boolean isOpen() {
			//start是new context()时获取的
			long time = System.currentTimeMillis() - this.start;
			//根据熔断器内部策略，判断是否有重试的次数（注意次数全局共享）
			boolean retryable = this.policy.canRetry(this.context);
			logger.info("===> retryable:"+retryable);
			if (!retryable) {//没有次数
				//间隔时间大于配置的resetTimeout时间，那么关闭熔断
				if (time > this.timeout) {
					logger.info("===> Closing");
					//重新创建context对象（次数归0）
					this.context = createDelegateContext(policy, getParent());
					//重置startTime
					this.start = System.currentTimeMillis();
					retryable = this.policy.canRetry(this.context);
				}
				else if (time < this.openWindow) {//间隔时间小于openTimeout，那么每次重置startTime，且打开断路器
					if (!hasAttribute(CIRCUIT_OPEN) || (Boolean) getAttribute(CIRCUIT_OPEN) == false) {
						logger.info("===> Opening circuit");
						setAttribute(CIRCUIT_OPEN, true);
						this.start = System.currentTimeMillis();
					}
					return true;
				}
				else{
					logger.info("===> 注意，这里是 大于openTimeout且小于resetTimeout，直接指向降级方案");//todo
				}
				//注意，这里是 大于openTimeout且小于resetTimeout，直接指向降级方案。
			}
			else {
				//可以理解为:配置(10s失败5次，开启熔断。但是大于10s都没失败5次，那么重新构建context对象)
				if (time > this.openWindow) {
					logger.info("===> Resetting context");
					this.start = System.currentTimeMillis();
					this.context = createDelegateContext(policy, getParent());
				}else{
					logger.info("===> else-小于10s且没失败5次的情况下，放行执行业务逻辑");
				}
				//else-小于10s且没失败5次的情况下，放行执行业务逻辑。
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Open: " + !retryable);
			}
			setAttribute(CIRCUIT_OPEN, !retryable);
			return !retryable;
		}

		@Override
		public int getRetryCount() {
			return this.context.getRetryCount();
		}

		@Override
		public String toString() {
			return this.context.toString();
		}

	}

}

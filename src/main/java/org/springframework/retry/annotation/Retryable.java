/*
 * Copyright 2014-2019 the original author or authors.
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

package org.springframework.retry.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for a method invocation that is retryable.
 *
 * @author Dave Syer
 * @author Artem Bilan
 * @author Gary Russell
 * @author Maksim Kita
 * @since 1.1
 *
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Retryable {

	/**
	 * 指定当前class中方法名称用于数据恢复，且指定的方法必须带有@Recover注解
	 * 如果当前class只有一个带有@Recover注解方法，则默认不设置recover值也会使用该方法，recover属性主要用于当存在多个@Recover注解方法场景
	 *
	 * Name of method in this class to use for recover. Method had to be marked with
	 * {@link Recover} annotation.
	 * @return the name of recover method
	 */
	String recover() default "";

	/**
	 * Retry interceptor bean name to be applied for retryable method. Is mutually
	 * exclusive with other attributes.
	 *
	 * interceptor指定bean名称，该属性和其它属性相互排斥
	 * @Bean("myInterceptor")
	 * public RetryOperationsInterceptor myInterceptor() {
	 * 		return RetryInterceptorBuilder.stateless()
	 *                 .backOffOptions(1000, 2, 5000)
	 *                 .maxAttempts(5).build();
	 * }
	 *
	 *
	 * @return the retry interceptor bean name
	 */
	String interceptor() default "";

	/**
	 * Exception types that are retryable. Synonym for includes(). Defaults to empty (and
	 * if excludes is also empty all exceptions are retried).
	 *
	 * 被注解的方法发生异常时会重试，value属性指定发生什么异常才会进行重试，默认空对所有异常进行重试
	 * value空默认对所有异常都进行重试，exclude可以用来排除什么异常不重试
	 * include属性等价于value
	 *
	 * @return exception types to retry
	 */
	Class<? extends Throwable>[] value() default {};

	/**
	 * Exception types that are retryable. Defaults to empty (and if excludes is also
	 * empty all exceptions are retried).
	 * @return exception types to retry
	 */
	Class<? extends Throwable>[] include() default {};

	/**
	 * Exception types that are not retryable. Defaults to empty (and if includes is also
	 * empty all exceptions are retried). If includes is empty but excludes is not, all
	 * not excluded exceptions are retried
	 * @return exception types not to retry
	 */
	Class<? extends Throwable>[] exclude() default {};

	/**
	 * A unique label for statistics reporting. If not provided the caller may choose to
	 * ignore it, or provide a default.
	 * @return the label for the statistics
	 */
	String label() default "";

	/**
	 * Flag to say that the retry is stateful: i.e. exceptions are re-thrown, but the
	 * retry policy is applied with the same policy to subsequent invocations with the
	 * same arguments. If false then retryable exceptions are not re-thrown.
	 *
	 * 是否有状态重试
	 *
	 * @return true if retry is stateful, default false
	 */
	boolean stateful() default false;

	/**
	 * 最大重试次数(包括第一次调用)，默认3
	 * @return the maximum number of attempts (including the first failure), defaults to 3
	 */
	int maxAttempts() default 3;

	/**
	 * 最大重试次数，支持el表达式
	 * 如从环境变量中取：maxAttemptsExpression = "${retry.max}"
	 * 或者调用bean方法计算：maxAttemptsExpression = "#{@testService01.getMax()}"
	 *
	 * @return an expression evaluated to the maximum number of attempts (including the
	 * first failure), defaults to 3 Overrides {@link #maxAttempts()}.
	 * @since 1.2
	 */
	String maxAttemptsExpression() default "";

	/**
	 * Specify the backoff properties for retrying this operation. The default is a simple
	 * {@link Backoff} specification with no properties - see it's documentation for
	 * defaults.
	 *
	 * 设置回避策略
	 *
	 * delay(等同于value)-指定延迟后重试，默认为1000L，
	 * 当未设置multiplier时，表示每隔delay的时间重试，直到重试次数到达maxAttempts设置的最大允许重试次数，
	 * 当设置了multiplier参数时，该值作为幂运算的初始值（delay = delay * multiplier）；
	 *
	 * multiplier-指定延迟倍数，默认为0，表示固定暂停1秒后进行重试。比如delay=5000L,multiplier=2时，第一次重试为5秒后，第二次为10秒，第三次为20秒
	 *
	 *
	 * @return a backoff specification
	 */
	Backoff backoff() default @Backoff();

	/**
	 * Specify an expression to be evaluated after the
	 * {@code SimpleRetryPolicy.canRetry()} returns true - can be used to conditionally
	 * suppress the retry. Only invoked after an exception is thrown. The root object for
	 * the evaluation is the last {@code Throwable}. Other beans in the context can be
	 * referenced. For example: <pre class=code>
	 *  {@code "message.contains('you can retry this')"}.
	 * </pre> and <pre class=code>
	 *  {@code "@someBean.shouldRetry(#root)"}.
	 * </pre>
	 *
	 *
	 * 抛出异常中包含特定字符串才会进行重试：exceptionExpression = "message.contains('this can be retried')"
	 * 调用bean方法判断是否支持该异常重试：exceptionExpression = "@exceptionChecker.shouldRetry(#root)"   #root代表抛出异常对象
	 *
	 * @return the expression.
	 * @since 1.2
	 */
	String exceptionExpression() default "";

	/**
	 * Bean names of retry listeners to use instead of default ones defined in Spring
	 * context
	 *
	 * 设置RetryListener的bean名称
	 *
	 * @return retry listeners bean names
	 */
	String[] listeners() default {};

}

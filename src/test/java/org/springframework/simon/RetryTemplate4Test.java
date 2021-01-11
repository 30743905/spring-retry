package org.springframework.simon;

import com.sun.org.apache.regexp.internal.RE;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryState;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.CircuitBreakerRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.DefaultRetryState;
import org.springframework.retry.support.RetryTemplate;

import java.net.ConnectException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 你搞忘写注释了
 *
 * @author zhang_zhang
 * @date 2021-01-04
 * @since 1.0.0
 */
public class RetryTemplate4Test {

    private Logger logger = Logger.getLogger(RetryTemplate4Test.class);

    /**
     * 1、对于Exception进行重试，最大重试3次；
     * 2、但是遇到DataAccessException异常则退出重试，并抛出
     */
    @Test
    public void test01() throws InterruptedException {

        /**
         * 6秒内失败3次，则触发熔断
         * 熔断的恢复时间时12s。
         */
        CircuitBreakerRetryPolicy retryPolicy =
                new CircuitBreakerRetryPolicy(new SimpleRetryPolicy(3));
        retryPolicy.setOpenTimeout(6000);
        retryPolicy.setResetTimeout(12000);


        //创建重试工具模板RetryTemplate
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(retryPolicy)//重试策略
                .build();


        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                //RetryCount从0开始
                logger.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount());
                //context.setAttribute("time", System.currentTimeMillis());
                context.setAttribute("state.global", true);
                return query(context.getRetryCount());
            }
        };

        // 如果RetryCallback执行出现指定异常, 并且超过最大重试次数依旧出现指定异常的话,就执行RecoveryCallback动作
        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                logger.info("*******************>>>do recory operation");
                return 0.123;
            }
        };

        /**
         * rollbackClassifier == null:即对所有异常都直接抛出
         */
        RetryState state = new DefaultRetryState("circuit", false);

        for(int i=0; i<50; i++){
            try {
                logger.info("begin===================>");
                Double result = retryTemplate.execute(retryCallback, recoveryCallback, state);
                logger.info("获取到返回值++>>>："+result);
            } catch (Exception e) {
                logger.warn("执行异常::>>>"+e.getMessage());
            }
            TimeUnit.SECONDS.sleep(1);
        }
        System.out.println("========finish");
    }


    private Double query(int count) throws Exception{
        System.out.println("============执行业务逻辑");
        throw new ConnectException("exception");
    }





}
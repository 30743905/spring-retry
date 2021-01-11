package org.springframework.simon;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.net.ConnectException;
import java.util.Collections;

/**
 * 你搞忘写注释了
 *
 * @author zhang_zhang
 * @date 2021-01-04
 * @since 1.0.0
 */
public class RetryTemplateTest {

    private Logger logger = Logger.getLogger(RetryTemplateTest.class);


    @Test
    public void test01() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(5);

        //创建重试回避策略
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(2000);

        //创建重试工具模板RetryTemplate
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)//重试策略
                .customBackoff(backOffPolicy)//回避策略
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Object doWithRetry(RetryContext context) throws Exception {
                //RetryCount从0开始
                logger.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount());
                //设置context一些属性,给RecoveryCallback传递一些属性
                context.setAttribute("time", System.currentTimeMillis());
                return getPrice(context.getRetryCount());
            }
        };


        try {
            Double ret = retryTemplate.execute(retryCallback);
            logger.info("获取到返回值："+ret);
        } catch (Exception e) {
            logger.error("执行异常", e);
        }

    }


    private Double getPrice(int count) throws Exception{
        /*if(count > 0){
            throw new Exception("other exception");
        }else if(count == 0){
            throw new ConnectException("exception");
        }*/
        return Math.random();
    }

}
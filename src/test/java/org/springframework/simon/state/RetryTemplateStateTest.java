package org.springframework.simon.state;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryState;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.DefaultRetryState;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.net.ConnectException;
import java.util.Collections;

/**
 * 你搞忘写注释了
 *
 * @author zhang_zhang
 * @date 2021-01-22
 * @since 1.0.0
 */
public class RetryTemplateStateTest {

    private Logger log = Logger.getLogger(RetryTemplateStateTest.class);


    /**
     * 1、对于Exception进行重试，最大重试3次；
     * 2、但是遇到DataAccessException异常则退出重试，直接将该异常向外抛出（即为回滚）
     */
    @Test
    public void test01() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.<Class<? extends Throwable>, Boolean>singletonMap(Exception.class, true));

        //创建重试回避策略
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(2000);

        //当前状态的名称，当把状态放入缓存时，通过该key查询获取
        Object key = "mykey";
        //是否每次都重新生成上下文还是从缓存中查询，即是否为全局模式（如熔断器策略时从缓存中查询）
        boolean isForceRefresh = true;  //true为每次重新生成
        //对DataAccessException进行回滚
        BinaryExceptionClassifier rollbackClassifier =
                new BinaryExceptionClassifier(Collections.<Class<? extends Throwable>>singleton(DataAccessException.class));
        RetryState state = new DefaultRetryState(key, isForceRefresh, rollbackClassifier);


        //创建重试工具模板RetryTemplate
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)//重试策略
                .customBackoff(backOffPolicy)//回避策略
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                //RetryCount从0开始
                log.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount());
                //设置context一些属性,给RecoveryCallback传递一些属性
                context.setAttribute("time", System.currentTimeMillis());
                context.setAttribute("state.global", true);
                return query(context.getRetryCount());
            }
        };

        // 如果RetryCallback执行出现指定异常, 并且超过最大重试次数依旧出现指定异常的话,就执行RecoveryCallback动作
        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                System.out.println("time:"+System.currentTimeMillis()+"*******************>>>do recory operation");
                System.out.println(context.getAttribute("key1"));
                System.out.println("last:"+context.getLastThrowable());

                //context.setAttribute("state.global", true);
                //return 0.123;
                throw new RuntimeException("recover exception");
            }
        };

        for(int i=0;i<3;i++){
            try {
                System.out.println("=======begin");
                Double ret = retryTemplate.execute(retryCallback, null, state);
                log.info("获取到返回值++>>>："+ret);
            } catch (Exception e) {
                log.error("执行异常::>>>", e);
            }
        }

        System.out.println("========finish");

    }


    @Test
    public void test02() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(Exception.class, true));

        //创建重试回避策略
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(2000);

        //当前状态的名称，当把状态放入缓存时，通过该key查询获取
        Object key = "mykey";
        //是否每次都重新生成上下文还是从缓存中查询，即是否为全局模式（如熔断器策略时从缓存中查询）
        boolean isForceRefresh = true;  //true为每次重新生成
        //对DataAccessException进行回滚
        BinaryExceptionClassifier rollbackClassifier =
                new BinaryExceptionClassifier(Collections.singleton(IllegalTransactionStateException.class));
        RetryState state = new DefaultRetryState(key, isForceRefresh, rollbackClassifier);


        //创建重试工具模板RetryTemplate
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)//重试策略
                .customBackoff(backOffPolicy)//回避策略
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                log.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount());
                context.setAttribute("time", System.currentTimeMillis());
                //context.setAttribute("state.global", true);
                return query(context.getRetryCount());
            }
        };

        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                System.out.println("time:"+System.currentTimeMillis()+"*******************>>>do recory operation");
                System.out.println(context.getAttribute("key1"));
                System.out.println("last:"+context.getLastThrowable());

                //context.setAttribute("state.global", true);
                //return 0.123;
                throw new RuntimeException("recover exception");
            }
        };

        for(int i=0;i<3;i++){
            try {
                System.out.println("=======begin");
                Double ret = retryTemplate.execute(retryCallback, null, state);
                log.info("获取到返回值++>>>："+ret);
            } catch (Exception e) {
                log.error("执行异常::>>>", e);
            }
        }

        System.out.println("========finish");

    }





    private Double query(int count) throws Exception{
        /*if(count > 0){
            throw new DataFormatException("other exception");
        }else if(count == 0){
            throw new ConnectException("exception");
        }*/
        log.info("================>>>>业务逻辑开始执行");
        throw new IllegalTransactionStateException("exception");
        //return Math.random();
    }

}
package org.springframework.simon.state;

import org.apache.log4j.Logger;
import org.junit.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryState;
import org.springframework.retry.annotation.CircuitBreaker;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.DefaultRetryState;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.simon.exception.MyException;

import java.net.ConnectException;
import java.util.Collections;

/**
 * 你搞忘写注释了
 *
 * @author zhang_zhang
 * @date 2021-01-22
 * @since 1.0.0
 */
public class RetryTemplateState2Test {

    private Logger log = Logger.getLogger(RetryTemplateState2Test.class);

    private Double query(int count) throws MyException {
        long time = System.currentTimeMillis();
        log.info("================>>>>业务逻辑开始执行, time:"+time);
        throw new MyException("time:"+time);
        /*if(time % 3 != 0){
            throw new MyException("time:"+time);
        }
        return Math.random();*/
    }


    private Double query1(int count) throws MyException {
        long time = System.currentTimeMillis();
        log.info("================>>>>业务逻辑开始执行, time:"+time+", count:" + count);
        throw new MyException("time:"+time);
    }

    /**
     * 无状态 && 无recoveryCallback
     * 重试失败：抛出业务逻辑中抛出的异常
     */
    @Test
    public void test01() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(MyException.class, true));

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
        RetryCallback<Double, MyException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws MyException {
                //RetryCount从0开始
                log.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount());
                //设置context一些属性,给RecoveryCallback传递一些属性
                //context.setAttribute("state.global", true);
                return query1(context.getRetryCount());
            }
        };


        try {
            System.out.println("=======begin");
            Double ret = retryTemplate.execute(retryCallback);
            log.info("获取到返回值++>>>："+ret);
        } catch (Exception e) {
            log.error("执行异常::>>>:"+e.getClass(), e);
        }

        System.out.println("========finish");
    }


    private Double query2(int count) throws MyException {
        long time = System.currentTimeMillis();
        log.info("================>>>>业务逻辑开始执行, time:"+time);
        throw new MyException("time:"+time);
    }

    /**
     * 无状态 && recoveryCallback:重试次数最大 OR 不是重试异常  都会导致重试失败，最终都要走recoveryCallback流程，recoveryCallback不能跑非RuntimeException类型异常
     * 有状态 && recoveryCallback:如果异常满足state.rollbackClassifier，则直接抛出异常而不会走recoveryCallback流程
     */
    @Test
    public void test02() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(MyException.class, true));

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
            public Double doWithRetry(RetryContext context) throws Exception {
                //RetryCount从0开始
                log.info("开始执行业务逻辑，RetryCount:" + context.getRetryCount());
                return query2(context.getRetryCount());
            }
        };

        // 如果RetryCallback执行出现指定异常, 并且超过最大重试次数依旧出现指定异常的话,就执行RecoveryCallback动作
        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                System.out.println("time:" + System.currentTimeMillis() + "*******************>>>do recory operation");
                System.out.println("last exception:" + context.getLastThrowable());
                throw new RuntimeException("recover exception");
            }
        };

        try {
            System.out.println("=======begin");
            Double ret = retryTemplate.execute(retryCallback, recoveryCallback);
            log.info("获取到返回值++>>>：" + ret);
        } catch (Exception e) {
            log.error("执行异常::>>>", e);
        }

        System.out.println("========finish");

    }


    private Double query3(int count) throws MyException {
        long time = System.currentTimeMillis();
        log.info("================>>>>业务逻辑开始执行, time:"+time);
        throw new MyException("time:"+time);
    }

    /**
     * MyException走RecoveryCallback流程(不重试)
     * IllegalAccessException则直接抛出(不走RecoveryCallback流程)
     */
    @Test
    public void test03() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(MyException.class, true));

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
                new BinaryExceptionClassifier(Collections.singleton(IllegalAccessException.class));
        RetryState state = new DefaultRetryState(key, isForceRefresh, rollbackClassifier);


        //创建重试工具模板RetryTemplate
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)//重试策略
                .customBackoff(backOffPolicy)//回避策略
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                log.info("开始执行业务逻辑，RetryCount:" + context.getRetryCount());
                context.setAttribute("time", System.currentTimeMillis());
                //context.setAttribute("state.global", false); //todo  有区别
                return query3(context.getRetryCount());
            }
        };

        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                System.out.println("time:" + System.currentTimeMillis() + "*******************>>>do recory operation");
                System.out.println(context.getAttribute("key1"));
                System.out.println("last:" + context.getLastThrowable());
                throw new RuntimeException("recover exception");
            }
        };

        try {
            System.out.println("=======begin");
            Double ret = retryTemplate.execute(retryCallback, recoveryCallback, state);
            log.info("获取到返回值++>>>：" + ret);
        } catch (Exception e) {
            log.error("执行异常::>>>", e);
        }

        System.out.println("========finish");

    }


    private Double query4(int count) throws MyException {
        long time = System.currentTimeMillis();
        log.info("================>>>>业务逻辑开始执行, time:"+time);
        throw new MyException("time:"+time);
    }

    /**
     * 有状态：
     *      isForceRefresh=false时使用全局context，需要设置context.setAttribute("state.global", false or true);
     *      state.global控制context是否从retryContextCache移除
     *      isForceRefresh控制获取context是否从缓存获取
     */
    @Test
    public void test04() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(ConnectException.class, true));

        //创建重试回避策略
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(2000);

        //当前状态的名称，当把状态放入缓存时，通过该key查询获取
        Object key = "mykey";
        //是否每次都重新生成上下文还是从缓存中查询，即是否为全局模式（如熔断器策略时从缓存中查询）
        boolean isForceRefresh = false;  //true为每次重新生成
        //对DataAccessException进行回滚
        BinaryExceptionClassifier rollbackClassifier =
                new BinaryExceptionClassifier(Collections.singleton(MyException.class));
        RetryState state = new DefaultRetryState(key, isForceRefresh, rollbackClassifier);


        //创建重试工具模板RetryTemplate
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)//重试策略
                .customBackoff(backOffPolicy)//回避策略
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                log.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount()+", hashcode:"+System.identityHashCode(context));
                context.setAttribute("time", System.currentTimeMillis());
                //context.setAttribute("state.global", false);
                return query4(context.getRetryCount());
            }
        };

        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                System.out.println("time:"+System.currentTimeMillis()+"*******************>>>do recory operation");
                System.out.println(context.getAttribute("key1"));
                System.out.println("last:"+context.getLastThrowable());
                throw new RuntimeException("recover exception");
            }
        };

        for(int i=0;i<3;i++){
            try {
                System.out.println("=======begin:"+i);
                Double ret = retryTemplate.execute(retryCallback, recoveryCallback, state);
                log.info("获取到返回值++>>>："+ret);
            } catch (Exception e) {
                log.error("执行异常::>>>", e);
            }
        }

        System.out.println("========finish");

    }


    @CircuitBreaker
    @Test
    public void transactionRollbackTest() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(Exception.class, true));

        //创建重试回避策略
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(2000);

        //对DataAccessException异常直接抛出不进行重试，外界感知后如事务回滚等
        BinaryExceptionClassifier rollbackClassifier =
                new BinaryExceptionClassifier(Collections.singleton(DataIntegrityViolationException.class));
        RetryState state = new DefaultRetryState("mykey", false, rollbackClassifier);

        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)
                .customBackoff(backOffPolicy)
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                log.info("开始执行业务逻辑，RetryCount:" + context.getRetryCount());
                context.setAttribute("time", System.currentTimeMillis());
                //context.setAttribute("state.global", false);
                return query(context.getRetryCount());
            }
        };

        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                Double defaultValue = 0.00;
                return defaultValue;
            }
        };

        try {
            //state参数传入
            Double ret = retryTemplate.execute(retryCallback, recoveryCallback, state);
            log.info("获取到返回值++>>>：" + ret);
        } catch (Exception e) {
            //感知异常，进行事务回滚...
        }
    }


    @Test
    public void test06() {
        //创建重试策略
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
                Collections.singletonMap(Exception.class, true));

        //创建重试回避策略
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(100);
        backOffPolicy.setMaxInterval(2000);

        //对DataAccessException进行回滚
        BinaryExceptionClassifier rollbackClassifier =
                new BinaryExceptionClassifier(Collections.singleton(DataIntegrityViolationException.class));
        RetryState state = new DefaultRetryState("mykey", false, rollbackClassifier);

        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)//重试策略
                .customBackoff(backOffPolicy)//回避策略
                .build();

        //RetryCallback：包装用于执行的业务逻辑
        RetryCallback<Double, ConnectException> retryCallback = new RetryCallback() {
            public Double doWithRetry(RetryContext context) throws Exception {
                log.info("开始执行业务逻辑，RetryCount:"+context.getRetryCount());
                context.setAttribute("time", System.currentTimeMillis());
                //context.setAttribute("state.global", false);
                return query(context.getRetryCount());
            }
        };

        RecoveryCallback<Double> recoveryCallback = new RecoveryCallback<Double>() {
            public Double recover(RetryContext context) throws Exception {
                Double defaultValue = 0.00;
                return defaultValue;
            }
        };

        for(int i=0;i<3;i++){
            try {
                System.out.println("=======begin");
                Double ret = retryTemplate.execute(retryCallback, recoveryCallback, state);
                log.info("获取到返回值++>>>："+ret);
            } catch (Exception e) {
                log.error("执行异常::>>>", e);
            }
        }

        System.out.println("========finish");

    }


}
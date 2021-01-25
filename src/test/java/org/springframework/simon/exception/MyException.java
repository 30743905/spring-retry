package org.springframework.simon.exception;

/**
 * 你搞忘写注释了
 *
 * @author zhang_zhang
 * @date 2021-01-25
 * @since 1.0.0
 */
public class MyException extends Exception{
    public MyException(String message) {
        super(message);
    }
}
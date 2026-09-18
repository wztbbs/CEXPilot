package com.cexpilot.exception;

public class ExchangeException extends RuntimeException {

    private final String exchange;

    public ExchangeException(String exchange, String message) {
        super(exchange + ": " + message);
        this.exchange = exchange;
    }

    public ExchangeException(String exchange, String message, Throwable cause) {
        super(exchange + ": " + message, cause);
        this.exchange = exchange;
    }

    public String getExchange() {
        return exchange;
    }
}

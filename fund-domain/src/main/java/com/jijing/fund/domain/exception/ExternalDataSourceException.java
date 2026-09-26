package com.jijing.fund.domain.exception;

/**
 * 外部数据源（基金数据、行情、基金发现等第三方接口）调用失败或返回不合规数据时抛出的非受检异常，
 * 携带一个稳定的错误码供上层映射为对外错误响应。构造时不校验参数，错误码和消息允许为 null。
 */
public class ExternalDataSourceException extends RuntimeException {
    private final String errorCode;

    /**
     * 使用错误码和面向日志的消息创建异常，不附带底层原因。
     * 参数不做校验：errorCode 为 null 时 {@link #errorCode()} 也返回 null。
     */
    public ExternalDataSourceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码、消息和底层原因（如 HTTP 或超时异常）创建异常，保留原始异常链以便排查。
     * 参数不做校验：cause 为 null 时等同于没有原因。
     */
    public ExternalDataSourceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 返回构造时传入的错误码，原样返回，可能为 null。 */
    public String errorCode() {
        return errorCode;
    }
}

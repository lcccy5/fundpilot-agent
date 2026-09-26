package com.jijing.fund.interfaces.api;

/**
 * 对外统一响应信封。成功和失败都携带请求号，失败时数据固定为空。
 */
public record ApiResponse<T>(String code, String message, String requestId, T data) {
    /**
     * 组装成功信封。数据允许为空，例如登出和删除。
     */
    public static <T> ApiResponse<T> success(String requestId, T data) {
        return new ApiResponse<>("SUCCESS", "success", requestId, data);
    }

    /**
     * 组装失败信封。不附带部分成功的数据，避免调用方误用残缺结果。
     */
    public static <T> ApiResponse<T> failure(String code, String message, String requestId) {
        return new ApiResponse<>(code, message, requestId, null);
    }
}

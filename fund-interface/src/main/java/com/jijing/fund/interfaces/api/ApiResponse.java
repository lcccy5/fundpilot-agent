package com.jijing.fund.interfaces.api;

public record ApiResponse<T>(String code, String message, String requestId, T data) {
    public static <T> ApiResponse<T> success(String requestId, T data) { return new ApiResponse<>("SUCCESS", "success", requestId, data); }
    public static <T> ApiResponse<T> failure(String code, String message, String requestId) { return new ApiResponse<>(code, message, requestId, null); }
}


package com.jijing.fund.agent.tool;

/**
 * 工具信封的终态。成功、调用方可修正的参数错误、数据未就绪和系统失败分开返回，
 * 避免模型把失败结果当成零值指标。本枚举本身不抛出异常。
 */
public enum ToolResultStatus { SUCCESS, USER_CORRECTABLE, DATA_NOT_READY, SYSTEM_FAILURE }

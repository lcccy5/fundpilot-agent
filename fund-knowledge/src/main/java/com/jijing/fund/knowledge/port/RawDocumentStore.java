package com.jijing.fund.knowledge.port;

/**
 * 原始字节存储。注册服务在仓库判定重复之前就会调用 {@link #store}，
 * 所以重复入库仍可能再写一次相同哈希的对象。空字节不会到达这里，注册校验会先拒绝。
 */
public interface RawDocumentStore {
    /**
     * 按内容哈希保存原文，返回之后读取用的存储键。
     */
    String store(String contentSha256, String originalFileName, byte[] content);

    /**
     * 按存储键读回原文。缺失或不可读时应抛出运行时异常，由入库失败分类处理。
     */
    byte[] read(String storageKey);
}

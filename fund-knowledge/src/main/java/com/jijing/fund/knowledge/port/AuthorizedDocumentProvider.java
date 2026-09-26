package com.jijing.fund.knowledge.port;

import java.net.URI;

/**
 * 从已授权的来源地址拉取原文。本模块不实现网络访问。
 * 拉到的空正文会在随后的注册校验中被拒绝，不会进入切块。
 */
public interface AuthorizedDocumentProvider {
    /**
     * 下载指定地址的文件。地址不被允许或传输失败时由实现抛出运行时异常。
     */
    FetchedDocument fetch(URI uri);

    /**
     * 一次下载的结果。正文在构造和读取时都会复制，调用方改数组不会影响已保存的副本。
     * 内容类型和文件名原样交给注册命令，空正文由注册校验拒绝。
     */
    record FetchedDocument(URI sourceUri, String fileName, String contentType, byte[] content) {
        /**
         * 复制正文。null 不能收成空数组，调用方必须传入实际字节；传入 null 会抛出 {@link NullPointerException}。
         */
        public FetchedDocument {
            content = content.clone();
        }

        /**
         * 返回正文的副本。
         */
        public byte[] content() {
            return content.clone();
        }
    }
}

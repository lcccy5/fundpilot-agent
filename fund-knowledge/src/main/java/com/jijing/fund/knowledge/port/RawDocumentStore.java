package com.jijing.fund.knowledge.port;

public interface RawDocumentStore {
    String store(String contentSha256, String originalFileName, byte[] content);
    byte[] read(String storageKey);
}

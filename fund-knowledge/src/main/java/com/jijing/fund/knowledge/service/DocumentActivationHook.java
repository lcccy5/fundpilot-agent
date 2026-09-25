package com.jijing.fund.knowledge.service;

import java.util.Collection;

/** Notifies callers after a document version becomes the searchable revision. */
@FunctionalInterface
public interface DocumentActivationHook {
    void activated(String documentId, String versionId, Collection<String> fundCodes);
}

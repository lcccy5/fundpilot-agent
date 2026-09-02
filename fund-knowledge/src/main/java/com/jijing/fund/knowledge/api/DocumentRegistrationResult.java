package com.jijing.fund.knowledge.api;

import com.jijing.fund.knowledge.domain.IngestionStatus;

public record DocumentRegistrationResult(String documentId, String versionId, String jobId,
        IngestionStatus status, boolean duplicate) {}

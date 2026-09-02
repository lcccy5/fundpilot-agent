package com.jijing.fund.knowledge.port;

import java.util.List;

public interface DocumentEmbeddingPort {
    String version();
    List<float[]> embed(List<String> texts);
    float[] embedQuery(String text);
}

package com.jijing.fund.testsupport.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 从 JSONL 文件装载检索评测样本。
 * 读文件失败时整批放弃；空行和以 # 开头的行会被跳过，不计入条数。
 */
public final class RagEvaluationDataset {
    private final ObjectMapper mapper;

    /**
     * 保存调用方传入的 JSON 映射器，真正读文件时才使用。
     * 传入 null 不会在这里失败，要到解析某一行时才会空指针。
     */
    public RagEvaluationDataset(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取路径上的 JSONL，并检查条数下限和 id 是否唯一。
     * 路径不存在、没有读权限或读取中断时抛出 IllegalStateException。
     * 有效行少于下限、id 重复，或某一行不是合法样本 JSON 时抛出 IllegalArgumentException，不返回已读到的部分。
     */
    public List<RagEvaluationCase> load(Path path, int minimumCases) {
        try (var lines = Files.lines(path)) {
            List<RagEvaluationCase> cases = lines
                    .map(String::trim)
                    .filter(v -> !v.isEmpty() && !v.startsWith("#"))
                    .map(this::parse)
                    .toList();
            if (cases.size() < minimumCases) {
                throw new IllegalArgumentException(
                        "RAG dataset requires at least " + minimumCases + " cases but has " + cases.size());
            }
            long distinct = cases.stream().map(RagEvaluationCase::id).distinct().count();
            if (distinct != cases.size()) {
                throw new IllegalArgumentException("RAG dataset contains duplicate ids");
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read RAG evaluation dataset", e);
        }
    }

    /**
     * 把单行 JSON 映射成一条评测样本。
     * 字段类型不对、JSON 损坏或映射器为空时抛出 IllegalArgumentException，并保留原始解析异常。
     */
    private RagEvaluationCase parse(String value) {
        try {
            return mapper.readValue(value, RagEvaluationCase.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RAG evaluation JSONL row", e);
        }
    }
}

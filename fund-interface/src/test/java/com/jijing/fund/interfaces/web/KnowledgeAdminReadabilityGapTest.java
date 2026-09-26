package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.knowledge.api.KnowledgeAdministrationUseCase;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import com.jijing.fund.knowledge.exception.KnowledgeConflictException;
import com.jijing.fund.knowledge.exception.KnowledgeNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 知识库管理接口的上传参数、资源缺失、冲突，以及索引治理未装配时的失败。
 * 角色校验在安全过滤器，不在本切片中。
 */
@WebMvcTest(controllers = KnowledgeAdminController.class, properties = "fund.knowledge.enabled=true")
@Import({KnowledgeAdminController.class, RequestIdFilter.class, GlobalExceptionHandler.class,
        FundQueryControllerTest.TestApplication.class})
class KnowledgeAdminReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean KnowledgeIngestionUseCase ingestion;
    @MockBean KnowledgeSearchUseCase search;
    @MockBean KnowledgeAdministrationUseCase administration;

    /**
     * 空文件返回 400。
     */
    @Test
    void uploadRejectsEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);
        mvc.perform(multipart("/internal/v1/knowledge/documents").file(file)
                        .param("title", "季报").param("documentType", "QUARTERLY_REPORT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INVALID_ARGUMENT"));
    }

    /**
     * 不在允许名单中的内容类型返回 400。
     */
    @Test
    void uploadRejectsUnknownContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.bin", "application/octet-stream", new byte[]{1});
        mvc.perform(multipart("/internal/v1/knowledge/documents").file(file)
                        .param("title", "季报").param("documentType", "QUARTERLY_REPORT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Only PDF, HTML and plain text are supported"));
    }

    /**
     * 来源地址无法解析时返回 400。
     */
    @Test
    void uploadRejectsMalformedSourceUri() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});
        mvc.perform(multipart("/internal/v1/knowledge/documents").file(file)
                        .param("title", "季报")
                        .param("documentType", "QUARTERLY_REPORT")
                        .param("sourceUri", "http://["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INVALID_ARGUMENT"));
    }

    /**
     * 缺少标题时返回 400。
     */
    @Test
    void uploadRequiresTitle() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});
        mvc.perform(multipart("/internal/v1/knowledge/documents").file(file).param("documentType", "QUARTERLY_REPORT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 文档不存在返回 404。
     */
    @Test
    void documentMissing() throws Exception {
        when(administration.document("doc-1")).thenThrow(new KnowledgeNotFoundException("missing document"));
        mvc.perform(get("/internal/v1/knowledge/documents/doc-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_FOUND"));
    }

    /**
     * 版本不存在返回 404。
     */
    @Test
    void versionMissing() throws Exception {
        when(administration.version("ver-1")).thenThrow(new KnowledgeNotFoundException("missing version"));
        mvc.perform(get("/internal/v1/knowledge/versions/ver-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_FOUND"));
    }

    /**
     * 入库任务不存在返回 404。
     */
    @Test
    void jobMissing() throws Exception {
        when(administration.job("job-1")).thenThrow(new KnowledgeNotFoundException("missing job"));
        mvc.perform(get("/internal/v1/knowledge/ingestion-jobs/job-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_NOT_FOUND"));
    }

    /**
     * 任务状态不允许重试时返回 409。
     */
    @Test
    void retryConflict() throws Exception {
        when(administration.retry("job-1")).thenThrow(new KnowledgeConflictException("still running"));
        mvc.perform(post("/internal/v1/knowledge/ingestion-jobs/job-1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_CONFLICT"));
    }

    /**
     * 检索词为空白时返回 400。
     */
    @Test
    void searchRejectsBlankQuery() throws Exception {
        mvc.perform(post("/internal/v1/knowledge/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 检索参数不合法时返回 400。
     */
    @Test
    void searchIllegalArgument() throws Exception {
        when(search.search(any())).thenThrow(new IllegalArgumentException("window reversed"));
        mvc.perform(post("/internal/v1/knowledge/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"回撤\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INVALID_ARGUMENT"));
    }

    /**
     * 检索运行时失败返回 503。
     */
    @Test
    void searchRuntimeFailure() throws Exception {
        when(search.search(any())).thenThrow(new IllegalStateException("index down"));
        mvc.perform(post("/internal/v1/knowledge/search/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"回撤\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"));
    }

    /**
     * 未装配索引治理时，创建重建返回 503。
     */
    @Test
    void rebuildWithoutGovernance() throws Exception {
        mvc.perform(post("/internal/v1/knowledge/index-rebuilds"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"));
    }

    /**
     * 查询重建进度同样依赖治理组件，缺失时返回 503。
     */
    @Test
    void rebuildStatusWithoutGovernance() throws Exception {
        mvc.perform(get("/internal/v1/knowledge/index-rebuilds/rebuild-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"));
    }
}

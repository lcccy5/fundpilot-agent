package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.knowledge.api.KnowledgeAdministrationUseCase;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import com.jijing.fund.knowledge.port.AuthorizedDocumentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 授权导入在提供者存在时的参数和下游失败。提供者缺失的 503 由另一个切片覆盖。
 */
@WebMvcTest(controllers = KnowledgeAdminController.class, properties = "fund.knowledge.enabled=true")
@Import({KnowledgeAdminController.class, RequestIdFilter.class, GlobalExceptionHandler.class,
        FundQueryControllerTest.TestApplication.class})
class KnowledgeAdminImportReadabilityGapTest {
    private static final String BODY = """
            {"title":"季报","documentType":"QUARTERLY_REPORT","sourceName":"manual","sourceUri":"https://example.test/a.pdf"}
            """;
    @Autowired MockMvc mvc;
    @MockBean KnowledgeIngestionUseCase ingestion;
    @MockBean KnowledgeSearchUseCase search;
    @MockBean KnowledgeAdministrationUseCase administration;
    @MockBean AuthorizedDocumentProvider provider;

    /**
     * 标题缺失时返回 400，不会调用授权下载。
     */
    @Test
    void importRejectsBlankTitle() throws Exception {
        mvc.perform(post("/internal/v1/knowledge/documents/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"documentType\":\"QUARTERLY_REPORT\",\"sourceName\":\"manual\",\"sourceUri\":\"https://example.test/a.pdf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 下载器认为地址不合法时返回 400。
     */
    @Test
    void importProviderRejectsUri() throws Exception {
        when(provider.fetch(any())).thenThrow(new IllegalArgumentException("uri rejected"));
        mvc.perform(post("/internal/v1/knowledge/documents/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INVALID_ARGUMENT"));
    }

    /**
     * 下载或入库的运行时失败返回 503。
     */
    @Test
    void importProviderUnavailable() throws Exception {
        when(provider.fetch(any())).thenThrow(new IllegalStateException("provider down"));
        mvc.perform(post("/internal/v1/knowledge/documents/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"));
    }
}

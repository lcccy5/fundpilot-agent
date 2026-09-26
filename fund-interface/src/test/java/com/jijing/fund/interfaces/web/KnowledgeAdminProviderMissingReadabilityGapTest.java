package com.jijing.fund.interfaces.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.knowledge.api.KnowledgeAdministrationUseCase;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 授权文档提供者没有装配时，导入接口返回 503。
 */
@WebMvcTest(controllers = KnowledgeAdminController.class, properties = "fund.knowledge.enabled=true")
@Import({KnowledgeAdminController.class, RequestIdFilter.class, GlobalExceptionHandler.class,
        FundQueryControllerTest.TestApplication.class})
class KnowledgeAdminProviderMissingReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean KnowledgeIngestionUseCase ingestion;
    @MockBean KnowledgeSearchUseCase search;
    @MockBean KnowledgeAdministrationUseCase administration;

    /**
     * 请求体合法也不能导入，因为没有可调用的下载器。
     */
    @Test
    void importWithoutProviderIsUnavailable() throws Exception {
        mvc.perform(post("/internal/v1/knowledge/documents/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"季报","documentType":"QUARTERLY_REPORT","sourceName":"manual","sourceUri":"https://example.test/a.pdf"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Authorized document provider is disabled"));
    }
}

package com.jijing.fund.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 用真实过滤链核对两个用户的组合、自选和运行隔离，以及重复导入不会多写交易。未打开环境变量时不执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="RUN_MYSQL_INTEGRATION_TESTS", matches="true")
class V3UserPortfolioMysqlIT {
    private static final String PLAN_GOAL="\u6bd4\u8f83 000001 110022 161725 \u5e76\u7ed3\u5408\u6211\u7684\u7ec4\u5408\u751f\u6210\u62a5\u544a";
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 用户 B 不能读取用户 A 的组合、自选和运行；同一账单提交两次只保留一笔交易。
     */
    @Test void twoUsersAreIsolatedAndImportIsIdempotent() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals("jijing_agent_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        String suffix=Long.toString(System.nanoTime()%1_000_000_000L);
        String userA="usera"+suffix; String userB="userb"+suffix; String password="correct-horse-battery";
        String tokenA=register(userA,password); String tokenB=register(userB,password);
        jakarta.servlet.http.Cookie refresh=registerRaw(userA+"z",password).getResponse().getCookie("fund_refresh");
        mvc.perform(post("/api/v1/auth/refresh").header("X-Request-Id","refresh-a").cookie(refresh)).andExpect(status().isOk());
        String portfolioId=createPortfolio(tokenA,"gate-"+suffix);
        mvc.perform(get("/api/v1/portfolios/"+portfolioId+"/positions").header("Authorization","Bearer "+tokenB).header("X-Request-Id","idor-b"))
                .andExpect(status().isNotFound());
        MvcResult group=mvc.perform(post("/api/v1/watchlists").header("Authorization","Bearer "+tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"core\"}"))
                .andExpect(status().isOk()).andReturn();
        String groupId=com.jayway.jsonpath.JsonPath.read(group.getResponse().getContentAsString(),"$.data.groupId");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/watchlists/"+groupId)
                        .header("Authorization","Bearer "+tokenB).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hack\",\"version\":1}").header("X-Request-Id","wl-idor"))
                .andExpect(status().isNotFound());
        String csv="fundCode,transactionType,tradeDate,confirmDate,shares,grossAmount,fee,confirmedNav,externalReference\n000001,SUBSCRIPTION,2025-01-02,2025-01-03,100,1000,0,10,ext-"+suffix+"\n";
        byte[] bytes=csv.getBytes(StandardCharsets.UTF_8);
        commit(tokenA,portfolioId,preview(tokenA,portfolioId,bytes));
        commit(tokenA,portfolioId,preview(tokenA,portfolioId,bytes));
        mvc.perform(get("/api/v1/portfolios/"+portfolioId+"/transactions").header("Authorization","Bearer "+tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        MvcResult first=mvc.perform(post("/api/v1/portfolios/"+portfolioId+"/snapshots/rebuild").header("Authorization","Bearer "+tokenA)).andExpect(status().isOk()).andReturn();
        String hash1=com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(),"$.data.inputHash");
        MvcResult second=mvc.perform(post("/api/v1/portfolios/"+portfolioId+"/snapshots/rebuild").header("Authorization","Bearer "+tokenA)).andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(hash1,com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(),"$.data.inputHash"));
        String runId=submitRun(tokenA);
        mvc.perform(get("/api/v1/agent/runs/"+runId).header("Authorization","Bearer "+tokenB).header("X-Request-Id","run-idor"))
                .andExpect(status().isNotFound());
    }

    /**
     * 注册并取出访问令牌。
     */
    private String register(String username,String password)throws Exception{
        return com.jayway.jsonpath.JsonPath.read(registerRaw(username,password).getResponse().getContentAsString(),"$.data.accessToken");
    }
    /**
     * 提交注册请求，保留刷新 Cookie 供后续轮换。
     */
    private MvcResult registerRaw(String username,String password)throws Exception{
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\""+username+"\",\"displayName\":\""+username+"\",\"password\":\""+password+"\"}"))
                .andExpect(status().isCreated()).andReturn();
    }
    /**
     * 为指定令牌创建一个组合并返回标识。
     */
    private String createPortfolio(String token,String name)throws Exception{
        MvcResult result=mvc.perform(post("/api/v1/portfolios").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\""+name+"\"}"))
                .andExpect(status().isOk()).andReturn();
        Object id=com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$.data.portfolioId");
        if(id instanceof java.util.Map<?,?> map)return String.valueOf(map.get("value"));
        return String.valueOf(id);
    }
    /**
     * 上传 CSV 预览，返回导入批次标识。
     */
    private String preview(String token,String portfolioId,byte[] csv)throws Exception{
        MvcResult result=mvc.perform(multipart("/api/v1/portfolios/"+portfolioId+"/imports/preview").file(new MockMultipartFile("file","sample.csv","text/csv",csv)).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$.data.batchId");
    }
    /**
     * 提交已经预览过的导入批次。
     */
    private void commit(String token,String portfolioId,String batchId)throws Exception{
        mvc.perform(post("/api/v1/portfolios/"+portfolioId+"/imports/"+batchId+"/commit").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
    /**
     * 替该用户提交一次月报目标运行并返回运行标识。
     */
    private String submitRun(String token)throws Exception{
        MvcResult result=mvc.perform(post("/api/v1/agent/runs").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\""+PLAN_GOAL+"\"}")).andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),"$.data.runId");
    }
}

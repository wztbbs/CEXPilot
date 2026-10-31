package com.cexpilot.api;

import com.cexpilot.conversation.*;
import com.cexpilot.config.ConversationConfig;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationBrowsingTest {
    JdbcTemplate jdbc; ConversationService service; MockMvc mvc;
    @BeforeEach void setup() {
        var data=new DriverManagerDataSource("jdbc:h2:mem:browse_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
        // Keep the in-memory database alive across JdbcTemplate connections.
        data.setUrl(data.getUrl()+";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(data);
        jdbc=new JdbcTemplate(data); service=new ConversationService(new ConversationRepository(jdbc),new ConversationConfig());
        mvc=MockMvcBuilders.standaloneSetup(new ConversationController(service)).build();
    }
    void message(String id,String turn) {
        jdbc.update("INSERT INTO conversation_query(conversation_id,query_no,question,answer,turn_id,turn_status) VALUES (?,1,'问题','答案',?,'NEEDS_CLARIFICATION')",id,turn);
    }
    @Test void creationSetsVisitorCookieAndFirstQuestionFillsTitleWithoutReusingOtherContext() throws Exception {
        var res=mvc.perform(post("/api/conversations").contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(cookie().exists("cexpilot_uid"))
                .andExpect(jsonPath("$.messages").isEmpty()).andReturn().getResponse();
        var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getContentAsString());
        String id=node.path("conversationId").asText(),visitor=res.getCookie("cexpilot_uid").getValue();
        String old=service.getOrCreateConversation("old","旧问题",visitor);message(old,"old-turn");
        assertEquals(id,service.getOrCreateConversation(id,"BTC 多少钱？",visitor));
        assertTrue(service.history(id).isEmpty());
        assertEquals("BTC 多少钱？",jdbc.queryForObject("SELECT title FROM conversation WHERE conversation_id=?",String.class,id));
        mvc.perform(get("/api/conversation/"+id).cookie(new Cookie("cexpilot_uid",visitor))).andExpect(jsonPath("$.parentTurnId").value(""));
    }
    @Test void listSearchPaginationAndHistoryAreScopedToVisitor() throws Exception {
        String a=service.getOrCreateConversation("a","BTC 比较","v1");message(a,"parent-a");
        String b=service.getOrCreateConversation("b","ETH 行情","v1");message(b,"parent-b");
        String foreign=service.getOrCreateConversation("foreign","BTC 私有问题","v2");message(foreign,"foreign-turn");
        service.createEmpty("v1");
        mvc.perform(get("/api/conversations").cookie(new Cookie("cexpilot_uid","v1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/conversations").param("search","BTC").cookie(new Cookie("cexpilot_uid","v1")))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].conversationId").value(a));
        mvc.perform(get("/api/conversations").param("limit","1").param("offset","1").cookie(new Cookie("cexpilot_uid","v1")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/conversation/a").cookie(new Cookie("cexpilot_uid","v1")))
                .andExpect(jsonPath("$.parentTurnId").value("parent-a")).andExpect(jsonPath("$.messages[0].answer").value("答案"));
        mvc.perform(get("/api/conversation/a").cookie(new Cookie("cexpilot_uid","v2"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversation/a")).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations")).andExpect(jsonPath("$.length()").value(0));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.getOrCreateConversation(a,"偷读","v2"));
    }
}

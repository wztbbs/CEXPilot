package com.cexpilot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AskRequestSourceTest {
    @Test void omittedAndNullDefaultToTrueAndOldConstructorsRemainCompatible() throws Exception {
        var mapper=new ObjectMapper();
        for(String field:List.of("",",\"isRealUser\":null",",\"isRealUser\":true"))
            assertTrue(mapper.readValue("{\"question\":\"q\""+field+"}",AskRequest.class).isRealUser());
        assertFalse(mapper.readValue("{\"question\":\"q\",\"isRealUser\":false}",AskRequest.class).isRealUser());
        assertTrue(new AskRequest(null,"q",null,null).isRealUser());
        assertTrue(new AskRequest(null,"q",null,null,null).isRealUser());
    }
    @Test void bothAskEndpointsPassSourceAndClarificationWithoutChangingConversation() throws Exception {
        for(boolean streaming:List.of(false,true)) {
            for(String field:List.of("",",\"isRealUser\":null",",\"isRealUser\":true",",\"isRealUser\":false")) {
                boolean expected=!field.endsWith("false");
                var service=mock(AskService.class);
                var response=new AskResponse("c1","trace","ok",null,0,0,0,null,false);
                when(service.ask(any(),any(),any(),any(),any(),any(),any(),anyBoolean())).thenAnswer(call->{
                    if(streaming) {
                        AskService.AskStreamListener listener=call.getArgument(5);
                        listener.onMeta("c1","trace",null);listener.onDone(response);
                    }
                    return response;
                });
                var mvc=MockMvcBuilders.standaloneSetup(new AskController(service,new SyncTaskExecutor())).build();
                String body="{\"conversationId\":\"c1\",\"question\":\"q\",\"parentTurnId\":\"p1\",\"clarificationAnswers\":{\"count\":\"最近三期\"}"+field+"}";
                var result=mvc.perform(post(streaming?"/api/ask/stream":"/api/ask").contentType("application/json").content(body))
                        .andExpect(status().isOk()).andReturn();
                if(streaming) assertTrue(mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString().contains("event:done"));
                verify(service).ask(eq("c1"),eq("q"),anyString(),isNull(),eq("p1"),any(),
                        argThat(answers->answers.get("count").toString().contains("最近三期")),eq(expected));
            }
        }
    }
}

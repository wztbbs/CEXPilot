package com.cexpilot.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HomeSuggestionsTest {
    @TempDir Path temporary;
    @Test void configuredPoolProvidesFourDistinctCategoriesWithoutMutatingSnapshot() throws Exception {
        var service=new HomeSuggestions(new ClassPathResource("home-suggestions.yml"));
        for(int i=0;i<40;i++) {
            var selected=service.select();
            assertEquals(4,selected.size());
            assertEquals("capabilities",selected.get(0).id());
            assertTrue(selected.get(0).pinned());
            assertEquals(4,selected.stream().map(HomeSuggestions.Suggestion::category).distinct().count());
            assertEquals(4,new HashSet<>(selected).size());
            assertTrue(selected.stream().allMatch(q->!q.query().isBlank()));
        }
        assertThrows(UnsupportedOperationException.class,()->service.select().clear());
    }
    @Test void fixedReplyMatchesWholeQuestionAndDoesNotLeakAnswerInRecommendations() throws Exception {
        var service=new HomeSuggestions(new ClassPathResource("home-suggestions.yml"));
        var reply=service.fixedReply("你能实现哪些功能？").orElseThrow();
        assertEquals(reply,service.fixedReply(" 你能实现哪些功能 ").orElseThrow());
        assertEquals(reply,service.fixedReply("你能实现哪些功能?").orElseThrow());
        assertTrue(reply.answer().contains("MarketPilot"));
        assertTrue(service.fixedReply("你能实现哪些功能？帮我查 BTC 价格").isEmpty());
        assertTrue(service.fixedReply("你能实现哪些监控功能？").isEmpty());
        var response=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(service.select());
        assertFalse(response.get(0).has("answer"));
    }
    @Test void pinningAndFixedAnswerAreIndependentAndPinsKeepConfigurationOrder() throws Exception {
        Path path=temporary.resolve("pinned.yml");
        Files.writeString(path,"""
                queries:
                  - {id: random, category: a, query: ordinary, answer: fixed}
                  - {id: first, category: b, query: first question, pinned: true}
                  - {id: second, category: c, query: second question, pinned: true, answer: second answer}
                """);
        var service=new HomeSuggestions(new FileSystemResource(path));
        assertEquals(List.of("first","second","random"),service.select().stream().map(HomeSuggestions.Suggestion::id).toList());
        assertTrue(service.fixedReply("first question").isEmpty());
        assertEquals("fixed",service.fixedReply("ordinary").orElseThrow().answer());
    }
    @Test void fileChangesTakeEffectOnlyAfterConstructingANewInstance() throws Exception {
        Path path=temporary.resolve("queries.yml");
        Files.writeString(path,"queries:\n  - {id: old, category: test, query: old question}\n");
        var loaded=new HomeSuggestions(new FileSystemResource(path));
        Files.writeString(path,"queries:\n  - {id: new, category: test, query: new question}\n");
        assertEquals("old question",loaded.select().get(0).query());
        assertEquals("new question",new HomeSuggestions(new FileSystemResource(path)).select().get(0).query());
    }
    @Test void invalidOrDuplicateConfigurationFailsAtStartup() throws Exception {
        Path path=temporary.resolve("invalid.yml");
        for(String text:List.of("queries: []","queries:\n  - {id: a, category: x}",
                "queries:\n  - {id: a, category: x, query: q}\n  - {id: a, category: x, query: q2}")) {
            Files.writeString(path,text);
            assertThrows(IllegalArgumentException.class,()->new HomeSuggestions(new FileSystemResource(path)));
        }
    }
}

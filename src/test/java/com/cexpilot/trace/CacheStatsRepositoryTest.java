package com.cexpilot.trace;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CacheStatsRepositoryTest {
    JdbcTemplate jdbc; CacheStatsRepository repository;
    final long start=Instant.parse("2026-10-30T01:00:00Z").toEpochMilli();
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:cache_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);repository=new CacheStatsRepository(jdbc);
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    void ask(String id,long time,String status) {
        jdbc.update("INSERT INTO ask_trace(trace_id,question,status,created_at) VALUES (?,'q',?,FROM_UNIXTIME(?))",id,status,time/1000);
    }
    void event(String id,String kind,Integer prompt,Integer cached) {
        event(id,kind,null,prompt,cached);
    }
    void event(String id,String kind,String stage,Integer prompt,Integer cached) {
        // 事件时间特意不在查询窗口：接口必须按 ask 创建时间选择，而不是按事件创建时间。
        jdbc.update("INSERT INTO trace_event(trace_id,seq,event_type,name,prompt_tokens,cached_tokens) VALUES (?,1,?,?,?,?)",id,kind,stage,prompt,cached);
    }
    @Test void aggregatesTokenWeightsAndIncludesFailedAsksButExcludesNonLlmEvents() {
        ask("a",start,"SUCCESS"); event("a","LLM_CALL",100,0);
        event("a","LLM_CALL",900,900); // 同一 ask 的重试/不同阶段也分别计数。
        ask("b",start+1000,"FAILED"); event("b","LLM_CALL",100,90);
        event("b","TOOL_CALL",999999,999999);
        ask("before",start-1000,"SUCCESS"); event("before","LLM_CALL",999999,999999);
        ask("end",start+2000,"SUCCESS"); event("end","LLM_CALL",999999,999999);
        assertEquals(new CacheStatsRepository.Totals(1100,990,3,0),repository.summarize(start,start+2000));
    }
    @Test void unknownAndInvalidUsageAreExcludedWhileZeroCacheIsMeasured() {
        ask("a",start,"RUNNING");
        event("a","LLM_CALL",100,0);
        event("a","LLM_CALL",100,null);
        event("a","LLM_CALL",null,10);
        event("a","LLM_CALL",0,0);
        event("a","LLM_CALL",100,101);
        event("a","LLM_CALL",100,-1);
        assertEquals(new CacheStatsRepository.Totals(100,0,1,5),repository.summarize(start,start+1000));
    }
    @Test void millisecondBoundariesRespectHalfOpenIntervalOnSecondPrecisionColumn() {
        ask("a",start,"SUCCESS");event("a","LLM_CALL",100,100);
        ask("b",start+1000,"SUCCESS");event("b","LLM_CALL",200,100);
        assertEquals(new CacheStatsRepository.Totals(200,100,1,0),repository.summarize(start+500,start+1500));
        assertEquals(new CacheStatsRepository.Totals(100,100,1,0),repository.summarize(start,start+1000));
        assertEquals(new CacheStatsRepository.Totals(0,0,0,0),repository.summarize(start+100,start+200));
    }
    @Test void noAsksOrNoCallsReturnZeroTotals() {
        assertEquals(new CacheStatsRepository.Totals(0,0,0,0),repository.summarize(start,start+1000));
        ask("a",start,"SUCCESS");
        assertEquals(new CacheStatsRepository.Totals(0,0,0,0),repository.summarize(start,start+1000));
    }
    @Test void stageGroupingIncludesRepairsMonitorPlanningAndUnknownNamesWithoutLosingCalls() {
        ask("a",start,"FAILED");
        event("a","LLM_CALL","router",100,0);
        event("a","LLM_CALL","dag_planner",10000,9900);
        event("a","LLM_CALL","dag_planner",11000,10000); // 修复轮
        event("a","LLM_CALL","monitor_planner",2000,1500);
        event("a","LLM_CALL","clarification_repair",500,400);
        event("a","LLM_CALL","answer",1000,0);
        event("a","LLM_CALL","answer",1000,null); // 输出阶段用量不完整
        event("a","LLM_CALL","future_stage",400,200);
        event("a","LLM_CALL",null,100,30);
        event("a","TOOL_CALL","dag_planner",999999,999999);
        ask("outside",start+1000,"SUCCESS");
        event("outside","LLM_CALL","router",999999,999999);
        var result=repository.summarizeByStage(start,start+1000);
        assertEquals(java.util.List.of("route","plan","answer","other"),new java.util.ArrayList<>(result.stages().keySet()));
        assertEquals(new CacheStatsRepository.Totals(100,0,1,0),result.stages().get("route"));
        assertEquals(new CacheStatsRepository.Totals(23500,21800,4,0),result.stages().get("plan"));
        assertEquals(new CacheStatsRepository.Totals(1000,0,1,1),result.stages().get("answer"));
        assertEquals(new CacheStatsRepository.Totals(500,230,2,0),result.stages().get("other"));
        assertEquals(new CacheStatsRepository.Totals(25100,22030,8,1),result.total());
    }
    @Test void knownStageAliasesMergeAndMissingUsageRemainsWithinItsOwnStage() {
        ask("a",start,"RUNNING");
        for(String name:java.util.List.of("route","intent_router")) event("a","LLM_CALL",name,100,50);
        for(String name:java.util.List.of("plan","planner")) event("a","LLM_CALL",name,100,50);
        event("a","LLM_CALL","router",100,101);
        event("a","LLM_CALL","dag_planner",null,null);
        event("a","LLM_CALL","answer",0,0);
        var result=repository.summarizeByStage(start,start+1000);
        assertEquals(new CacheStatsRepository.Totals(200,100,2,1),result.stages().get("route"));
        assertEquals(new CacheStatsRepository.Totals(200,100,2,1),result.stages().get("plan"));
        assertEquals(new CacheStatsRepository.Totals(0,0,0,1),result.stages().get("answer"));
        assertEquals(new CacheStatsRepository.Totals(400,200,4,3),result.total());
    }
    @Test void emptyRangeStillReturnsAllFourStages() {
        var result=repository.summarizeByStage(start,start+1000);
        assertEquals(4,result.stages().size());
        assertTrue(result.stages().values().stream().allMatch(t->t.equals(new CacheStatsRepository.Totals(0,0,0,0))));
    }
}

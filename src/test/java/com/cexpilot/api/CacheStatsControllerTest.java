package com.cexpilot.api;

import com.cexpilot.trace.CacheStatsRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.LinkedHashMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CacheStatsControllerTest {
    CacheStatsRepository repository; MockMvc mvc;
    final long end=Instant.parse("2026-10-31T00:00:00Z").toEpochMilli(),start=end-5*3600_000L;
    @BeforeEach void setup() {
        repository=mock(CacheStatsRepository.class);
        mvc=MockMvcBuilders.standaloneSetup(new CacheStatsController(repository,Clock.fixed(Instant.ofEpochMilli(end),ZoneOffset.UTC))).build();
    }
    private CacheStatsRepository.Breakdown breakdown(CacheStatsRepository.Totals totals) {
        var stages=new LinkedHashMap<String,CacheStatsRepository.Totals>();
        for(String stage:java.util.List.of("route","plan","answer","other"))
            stages.put(stage,new CacheStatsRepository.Totals(0,0,0,0));
        stages.put("plan",totals);
        return new CacheStatsRepository.Breakdown(totals,stages);
    }
    @Test void acceptsExplicitRangeAndComputesWeightedPercentage() throws Exception {
        when(repository.summarizeByStage(start,end)).thenReturn(breakdown(new CacheStatsRepository.Totals(100000,88000,42,2)));
        mvc.perform(get("/api/stats/cache").param("startTime",""+start).param("endTime",""+end))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.startTime").value(start)).andExpect(jsonPath("$.endTime").value(end))
                .andExpect(jsonPath("$.cacheHitPercent").value(88.0)).andExpect(jsonPath("$.measuredCalls").value(42))
                .andExpect(jsonPath("$.unknownCalls").value(2));
    }
    @Test void omittedEndUsesServerClockAndNoDataReturnsNullNotZeroPercent() throws Exception {
        when(repository.summarizeByStage(start,end)).thenReturn(breakdown(new CacheStatsRepository.Totals(0,0,0,3)));
        String response=mvc.perform(get("/api/stats/cache").param("startTime",""+start)).andExpect(status().isOk())
                .andExpect(jsonPath("$.endTime").value(end)).andExpect(jsonPath("$.unknownCalls").value(3))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("\"cacheHitPercent\":null"));
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("\"route\":{\"promptTokens\":0,\"cachedTokens\":0,\"cacheHitPercent\":null"));
        verify(repository).summarizeByStage(start,end);
    }
    @Test void exposesEachStageWithoutLettingPlannerHideZeroCacheInOtherStages() throws Exception {
        var stages=new LinkedHashMap<String,CacheStatsRepository.Totals>();
        stages.put("route",new CacheStatsRepository.Totals(1000,0,10,0));
        stages.put("plan",new CacheStatsRepository.Totals(98000,90000,10,0));
        stages.put("answer",new CacheStatsRepository.Totals(1000,0,10,1));
        stages.put("other",new CacheStatsRepository.Totals(0,0,0,2));
        when(repository.summarizeByStage(start,end)).thenReturn(new CacheStatsRepository.Breakdown(
                new CacheStatsRepository.Totals(100000,90000,30,3),stages));
        mvc.perform(get("/api/stats/cache").param("startTime",""+start))
                .andExpect(status().isOk()).andExpect(jsonPath("$.promptTokens").value(100000))
                .andExpect(jsonPath("$.cachedTokens").value(90000)).andExpect(jsonPath("$.cacheHitPercent").value(90.0))
                .andExpect(jsonPath("$.stages.route.promptTokens").value(1000))
                .andExpect(jsonPath("$.stages.route.cachedTokens").value(0))
                .andExpect(jsonPath("$.stages.route.cacheHitPercent").value(0.0))
                .andExpect(jsonPath("$.stages.plan.promptTokens").value(98000))
                .andExpect(jsonPath("$.stages.plan.cachedTokens").value(90000))
                .andExpect(jsonPath("$.stages.plan.cacheHitPercent").value(91.84))
                .andExpect(jsonPath("$.stages.answer.cacheHitPercent").value(0.0))
                .andExpect(jsonPath("$.stages.answer.unknownCalls").value(1))
                .andExpect(jsonPath("$.stages.other.cacheHitPercent").isEmpty())
                .andExpect(jsonPath("$.stages.other.unknownCalls").value(2));
        verify(repository,times(1)).summarizeByStage(start,end);
    }
    @Test void rejectsMissingMalformedAndReversedRangesWithoutQueryingDatabase() throws Exception {
        mvc.perform(get("/api/stats/cache")).andExpect(status().isBadRequest());
        for(String value:java.util.List.of("x","1.5","-1",""+end,""+(end+1),"9223372036854775807"))
            mvc.perform(get("/api/stats/cache").param("startTime",value)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stats/cache").param("startTime",""+start).param("endTime","x")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stats/cache").param("startTime",""+start).param("endTime",""+start)).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}

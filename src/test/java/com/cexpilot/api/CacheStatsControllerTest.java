package com.cexpilot.api;

import com.cexpilot.trace.CacheStatsRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
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
    @Test void acceptsExplicitRangeAndComputesWeightedPercentage() throws Exception {
        when(repository.summarize(start,end)).thenReturn(new CacheStatsRepository.Totals(100000,88000,42,2));
        mvc.perform(get("/api/stats/cache").param("startTime",""+start).param("endTime",""+end))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.startTime").value(start)).andExpect(jsonPath("$.endTime").value(end))
                .andExpect(jsonPath("$.cacheHitPercent").value(88.0)).andExpect(jsonPath("$.measuredCalls").value(42))
                .andExpect(jsonPath("$.unknownCalls").value(2));
    }
    @Test void omittedEndUsesServerClockAndNoDataReturnsNullNotZeroPercent() throws Exception {
        when(repository.summarize(start,end)).thenReturn(new CacheStatsRepository.Totals(0,0,0,3));
        String response=mvc.perform(get("/api/stats/cache").param("startTime",""+start)).andExpect(status().isOk())
                .andExpect(jsonPath("$.endTime").value(end)).andExpect(jsonPath("$.unknownCalls").value(3))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(response.contains("\"cacheHitPercent\":null"));
        verify(repository).summarize(start,end);
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

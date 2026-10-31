package com.cexpilot.market.taker;

import com.cexpilot.market.BinanceClient;
import com.cexpilot.market.model.TakerVolumePoint;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BinanceSpotTakerVolumeSourceTest {
    static final long START = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
    static List<TakerVolumePoint> points(long start, int count) {
        var rows = new ArrayList<TakerVolumePoint>();
        for (int i=0;i<count;i++) rows.add(new TakerVolumePoint(start+i*300000L, BigDecimal.ONE, BigDecimal.TEN));
        return rows;
    }
    @Test void paginatesUsingSpotKlinesAndKeepsExactEndExclusive() {
        var client = mock(BinanceClient.class);
        long end = START + 1002L*300000;
        when(client.spotTakerVolumes(eq("BTCUSDT"), anyLong(), eq(end-1), eq(1000)))
                .thenAnswer(c -> points(c.getArgument(1), (long)c.getArgument(1)==START?1000:2));
        var result = new BinanceSpotTakerVolumeSource(client).fetch("BTC",START,end,Instant.ofEpochMilli(end));
        assertEquals(1002,result.points().size()); assertNull(result.abortReason());
        verify(client).spotTakerVolumes("BTCUSDT",START,end-1,1000);
        verify(client).spotTakerVolumes("BTCUSDT",START+1000L*300000,end-1,1000);
        verifyNoMoreInteractions(client);
    }
    @Test void stalledCursorAndExhaustedBudgetPreserveAbortReason() {
        var client=mock(BinanceClient.class);
        when(client.spotTakerVolumes(anyString(),anyLong(),anyLong(),eq(1000))).thenReturn(points(START,1000));
        var source=new BinanceSpotTakerVolumeSource(client);
        long end=START+5000L*300000;
        var stalled=source.fetch("BTC",START,end,Instant.ofEpochMilli(end));
        assertEquals(1000,stalled.points().size()); assertTrue(stalled.abortReason().contains("游标"));
        when(client.spotTakerVolumes(anyString(),anyLong(),anyLong(),eq(1000)))
                .thenAnswer(c -> points(c.getArgument(1),1000));
        var budget=source.fetch("BTC",START,end,Instant.ofEpochMilli(end));
        assertEquals(4000,budget.points().size()); assertTrue(budget.abortReason().contains("预算"));
    }
}

package com.cexpilot.market;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 阶段三 HTTP 计数器的 ThreadLocal 语义：未绑定空操作、绑定逐次计数、清除失效、线程隔离。 */
class HttpRequestCounterTest {

    @Test
    void 未绑定时递增为空操作() {
        assertDoesNotThrow(HttpRequestCounter::increment);
        HttpRequestCounter.clear();
        assertDoesNotThrow(HttpRequestCounter::increment);
    }

    @Test
    void 绑定后逐次计数清除后失效() {
        AtomicInteger counter = new AtomicInteger();
        HttpRequestCounter.bind(counter);
        HttpRequestCounter.increment();
        HttpRequestCounter.increment();
        assertEquals(2, counter.get());
        HttpRequestCounter.clear();
        HttpRequestCounter.increment();
        assertEquals(2, counter.get(), "清除后递增不再计数");
    }

    @Test
    void 工作线程的计数与主线程隔离() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        HttpRequestCounter.bind(counter);
        // DagExecutor 在工作线程 bind 本轮计数器；其他线程各自的 ThreadLocal 互不影响
        Thread other = new Thread(HttpRequestCounter::increment);
        other.start();
        other.join();
        assertEquals(0, counter.get(), "未绑定该计数器的线程递增不影响本线程计数");
        HttpRequestCounter.clear();
    }
}

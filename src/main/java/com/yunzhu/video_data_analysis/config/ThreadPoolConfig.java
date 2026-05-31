package com.yunzhu.video_data_analysis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 用于多智能体任务执行的自定义线程池。
 * <p>
 * 使用专用线程池而不是 {@link java.util.concurrent.ForkJoinPool#commonPool()}
 * 提供：
 * <ul>
 *   <li>线程隔离 — 智能体任务不会饿死其他组件</li>
 *   <li>命名线程 — 在线程转储/日志中更容易识别</li>
 *   <li>有界队列 — 负载下的背压而不是无限增长</li>
 *   <li>{@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} —
 *       当饱和时，调用者线程直接执行任务，
 *       提供自然的背压。</li>
 * </ul>
 */
@Configuration
public class ThreadPoolConfig {

    @Bean("agentTaskExecutor")
    public Executor agentTaskExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                cores * 2,                  // corePoolSize — 基线线程数（×2 加速rerank并发）
                cores * 4,                  // maximumPoolSize — 突发容量
                60L, TimeUnit.SECONDS,      // keepAliveTime — 空闲线程保留时间
                new LinkedBlockingQueue<>(200), // 有界队列防止OOM
                new NamedThreadFactory("agent-task"),
                new ThreadPoolExecutor.CallerRunsPolicy() // 背压
        );
    }

    /**
     * {@link java.util.concurrent.ThreadFactory}，为线程命名以便调试。
     */
    private static class NamedThreadFactory implements java.util.concurrent.ThreadFactory {

        private static final java.util.concurrent.atomic.AtomicInteger poolNum =
                new java.util.concurrent.atomic.AtomicInteger(1);

        private final String prefix;
        private final java.util.concurrent.atomic.AtomicInteger threadNum =
                new java.util.concurrent.atomic.AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + poolNum.get() + "-" + threadNum.getAndIncrement());
            t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}

package za.co.oip.crawling.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.crawling.CrawlerProperties;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.opportunities.domain.OpportunityRepository;

import java.time.Clock;
import java.time.LocalDate;

@Configuration
@EnableScheduling
public class CrawlSchedulingConfig {

    /** Bounded pool so crawls cannot exhaust threads; excess jobs queue. */
    @Bean(name = "crawlExecutor")
    TaskExecutor crawlExecutor(CrawlerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrentCrawls());
        executor.setMaxPoolSize(properties.maxConcurrentCrawls());
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("crawl-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /** Polls for due sources. Disabled with {@code oip.crawler.scheduler-enabled=false} (e.g. in tests). */
    @Component
    @ConditionalOnProperty(prefix = "oip.crawler", name = "scheduler-enabled", havingValue = "true")
    static class CrawlScheduler {

        private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);
        private final CrawlService crawlService;

        CrawlScheduler(CrawlService crawlService, CrawlerProperties properties) {
            // Unattended crawling must identify us honestly: source operators need a way to reach us before they block.
            if (properties.lacksRealContact()) {
                throw new IllegalStateException("Scheduled crawling is enabled but the crawler user agent has no real contact "
                        + "URL or e-mail (\"" + properties.userAgent() + "\"). Set OIP_CRAWLER_USER_AGENT, e.g. "
                        + "\"OIP-Crawler/1.0 (+https://oip.example.co.za/crawler)\" with your own domain, or set "
                        + "OIP_CRAWLER_SCHEDULER_ENABLED=false.");
            }
            this.crawlService = crawlService;
        }

        @Scheduled(initialDelayString = "${oip.crawler.initial-delay:PT1M}", fixedDelayString = "${oip.crawler.poll-interval:PT1M}")
        void pollDueSources() {
            int queued = crawlService.queueDueCrawls();
            if (queued > 0) {
                log.info("Queued {} scheduled crawl(s)", queued);
            }
        }
    }

    /** Marks opportunities whose closing moment has passed as EXPIRED. */
    @Component
    static class OpportunityLifecycleJob {

        private static final Logger log = LoggerFactory.getLogger(OpportunityLifecycleJob.class);
        private final OpportunityRepository repository;
        private final Clock clock;

        OpportunityLifecycleJob(OpportunityRepository repository, Clock clock) {
            this.repository = repository;
            this.clock = clock;
        }

        @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${oip.opportunities.expiry-interval:PT15M}")
        @Transactional
        public void expire() {
            int expired = repository.expireClosed(clock.instant(), LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA)));
            if (expired > 0) {
                log.info("Marked {} opportunities as EXPIRED", expired);
            }
        }
    }
}

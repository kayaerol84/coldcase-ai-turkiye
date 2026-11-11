package tr.coldcase.api.processing

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
@ConditionalOnProperty("app.worker.enabled", havingValue = "true", matchIfMissing = true)
class ProcessingWorkerConfig

/** Polls the job queue. Uploads never wait for processing (TR §13). */
@Component
@ConditionalOnProperty("app.worker.enabled", havingValue = "true", matchIfMissing = true)
class ProcessingWorker(private val processor: DocumentProcessor) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.worker.poll-interval:2s}")
    fun poll() {
        try {
            processor.drain(max = 50)
        } catch (e: Exception) {
            log.error("Worker poll failed", e)
        }
    }
}

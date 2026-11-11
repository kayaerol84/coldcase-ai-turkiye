package tr.coldcase.api.processing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

enum class JobType { EXTRACT_DOCUMENT, OCR_PAGES }

data class Job(
    val id: UUID,
    val caseId: UUID,
    val documentVersionId: UUID,
    val type: JobType,
    val payload: JsonNode,
    val attempts: Int,
    val maxAttempts: Int,
)

/**
 * PostgreSQL-backed job queue. Jobs are claimed with FOR UPDATE SKIP LOCKED so several workers can
 * run concurrently; a RUNNING job whose lease expired (worker crashed) becomes claimable again.
 */
@Component
class JobQueue(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    fun enqueue(caseId: UUID, versionId: UUID, type: JobType, payload: Map<String, Any?> = emptyMap()): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO processing_jobs (id, case_id, document_version_id, job_type, payload, status)
            VALUES (:id, :case, :v, :type, CAST(:payload AS jsonb), 'QUEUED')
            """.trimIndent()
        )
            .param("id", id).param("case", caseId).param("v", versionId).param("type", type.name)
            .param("payload", mapper.writeValueAsString(payload))
            .update()
        return id
    }

    /** Claims the next runnable job, optionally restricted to one document version (used by tests). */
    fun claim(workerId: String, lease: Duration, versionId: UUID? = null): Job? =
        jdbc.sql(
            """
            UPDATE processing_jobs j
               SET status = 'RUNNING', locked_by = :worker, locked_at = now(),
                   attempts = j.attempts + 1, started_at = coalesce(j.started_at, now())
             WHERE j.id = (
                   SELECT id FROM processing_jobs
                    WHERE ((status = 'QUEUED' AND run_after <= now())
                        OR (status = 'RUNNING' AND locked_at < now() - make_interval(secs => :leaseSeconds)))
                      AND (CAST(:version AS uuid) IS NULL OR document_version_id = CAST(:version AS uuid))
                    ORDER BY run_after, created_at
                    LIMIT 1
                      FOR UPDATE SKIP LOCKED)
            RETURNING j.id, j.case_id, j.document_version_id, j.job_type, j.payload, j.attempts, j.max_attempts
            """.trimIndent()
        )
            .param("worker", workerId)
            .param("leaseSeconds", lease.seconds.toDouble())
            .param("version", versionId)
            .query { rs, _ ->
                Job(
                    rs.getObject("id", UUID::class.java), rs.getObject("case_id", UUID::class.java),
                    rs.getObject("document_version_id", UUID::class.java), JobType.valueOf(rs.getString("job_type")),
                    mapper.readTree(rs.getString("payload")), rs.getInt("attempts"), rs.getInt("max_attempts"),
                )
            }
            .optional().orElse(null)

    fun complete(id: UUID) {
        jdbc.sql(
            "UPDATE processing_jobs SET status = 'SUCCEEDED', finished_at = now(), locked_by = NULL, locked_at = NULL, last_error = NULL WHERE id = :id"
        ).param("id", id).update()
    }

    /** Requeues with exponential backoff (5s · 2^(attempt-1), capped at 10 min) or fails permanently. */
    fun fail(job: Job, error: String, retryable: Boolean) {
        val retry = retryable && job.attempts < job.maxAttempts
        val backoff = backoffFor(job.attempts)
        jdbc.sql(
            """
            UPDATE processing_jobs
               SET status = :status, last_error = :error, locked_by = NULL, locked_at = NULL,
                   run_after = CASE WHEN :retry THEN now() + make_interval(secs => :backoff) ELSE run_after END,
                   finished_at = CASE WHEN :retry THEN NULL ELSE now() END
             WHERE id = :id
            """.trimIndent()
        )
            .param("status", if (retry) "QUEUED" else "FAILED")
            .param("error", error.take(1000))
            .param("retry", retry)
            .param("backoff", backoff.seconds.toDouble())
            .param("id", job.id)
            .update()
    }

    fun hasActive(versionId: UUID): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM processing_jobs WHERE document_version_id = :v AND status IN ('QUEUED','RUNNING'))")
            .param("v", versionId).query(Boolean::class.java).single()

    /** Most recent permanently failed job error for the version, if its latest job of [type] failed. */
    fun latestFailure(versionId: UUID, type: JobType): String? =
        jdbc.sql(
            """
            SELECT status, last_error FROM processing_jobs
             WHERE document_version_id = :v AND job_type = :t
             ORDER BY created_at DESC LIMIT 1
            """.trimIndent()
        )
            .param("v", versionId).param("t", type.name)
            .query { rs, _ -> if (rs.getString("status") == "FAILED") rs.getString("last_error") ?: "" else null }
            .optional().orElse(null)

    companion object {
        fun backoffFor(attempt: Int): Duration =
            Duration.ofSeconds(minOf(600L, 5L shl (attempt - 1).coerceIn(0, 10)))
    }
}

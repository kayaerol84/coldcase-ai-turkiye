package tr.coldcase.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app")
data class AppProperties(
    val documents: Documents = Documents(),
    val storage: Storage = Storage(),
    val worker: Worker = Worker(),
    val extraction: Extraction = Extraction(),
) {
    data class Documents(val maxPages: Int = 5000)

    data class Storage(
        val type: String = "filesystem",
        val filesystemRoot: String = "./data/objects",
        val s3: S3 = S3(),
    )

    data class S3(
        val endpoint: String? = null,
        val region: String = "eu-central-1",
        val bucket: String = "coldcase-originals",
        val accessKey: String? = null,
        val secretKey: String? = null,
        val pathStyle: Boolean = true,
        val createBucket: Boolean = false,
        val serverSideEncryption: Boolean = true,
    )

    data class Worker(
        val enabled: Boolean = true,
        val pollInterval: Duration = Duration.ofSeconds(2),
        val lease: Duration = Duration.ofMinutes(15),
    )

    data class Extraction(
        /** Pages with fewer non-whitespace characters of embedded text are sent to OCR. */
        val minTextChars: Int = 25,
        /** Pages whose embedded text scores below this (garbled font encodings) are sent to OCR. */
        val minQuality: Double = 0.6,
        val ocrBatchSize: Int = 10,
        val ocr: Ocr = Ocr(),
    )

    data class Ocr(
        val command: String = "tesseract",
        val languages: String = "tur",
        val dpi: Int = 300,
        val timeout: Duration = Duration.ofSeconds(180),
    )
}

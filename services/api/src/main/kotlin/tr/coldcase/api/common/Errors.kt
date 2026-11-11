package tr.coldcase.api.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/** Thrown for both "does not exist" and "not visible to you" so case existence never leaks. */
class NotFoundException(message: String = "Not found") : RuntimeException(message)

/** Thrown when a member is visible but lacks the permission for an action. */
class ForbiddenException(message: String = "Forbidden") : RuntimeException(message)

/**
 * Client error with a stable, machine-readable [code] the web app maps to a localised message.
 * [properties] are added to the problem body (identifiers only, never document content).
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val properties: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

class BadRequestException(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(e: ForbiddenException) = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(e.status, e.message).apply {
            setProperty("code", e.code)
            e.properties.forEach { (k, v) -> setProperty(k, v) }
        }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(e: MaxUploadSizeExceededException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the maximum upload size").apply {
            setProperty("code", "FILE_TOO_LARGE")
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed").apply {
            setProperty("code", "VALIDATION_FAILED")
            setProperty("errors", e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) })
        }
}

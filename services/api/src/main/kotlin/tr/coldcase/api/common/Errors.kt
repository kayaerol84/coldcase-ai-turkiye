package tr.coldcase.api.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Thrown for both "does not exist" and "not visible to you" so case existence never leaks. */
class NotFoundException(message: String = "Not found") : RuntimeException(message)

/** Thrown when a member is visible but lacks the permission for an action. */
class ForbiddenException(message: String = "Forbidden") : RuntimeException(message)

/** [code] is a stable, machine-readable identifier the web app maps to a localised message. */
class BadRequestException(val code: String, message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(e: ForbiddenException) = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(BadRequestException::class)
    fun badRequest(e: BadRequestException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message).apply { setProperty("code", e.code) }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed").apply {
            setProperty("code", "VALIDATION_FAILED")
            setProperty("errors", e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) })
        }
}

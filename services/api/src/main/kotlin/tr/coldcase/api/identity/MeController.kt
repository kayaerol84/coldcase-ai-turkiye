package tr.coldcase.api.identity

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class MeResponse(val id: UUID, val email: String?, val displayName: String?, val platformAdmin: Boolean)

@RestController
class MeController(private val users: UserService) {
    @GetMapping("/api/v1/me")
    fun me() = users.current().let { MeResponse(it.id, it.email, it.displayName, it.platformAdmin) }
}

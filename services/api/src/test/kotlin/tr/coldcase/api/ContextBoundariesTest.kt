package tr.coldcase.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Enforces the context map in docs/kb/domain/context-map.md: each top-level package (bounded context
 * or shared module) may only import the packages listed here. A new cross-context dependency fails
 * this test until it is a deliberate decision recorded in the context map and added below.
 * Removing a dependency also fails it, so the map never goes stale.
 */
class ContextBoundariesTest {

    private val allowed: Map<String, Set<String>> = mapOf(
        // Shared kernel / infrastructure
        "common" to setOf(),
        "config" to setOf(),
        "audit" to setOf(),
        "storage" to setOf("config", "common"),
        "embeddings" to setOf("config"),
        // Bounded contexts
        "identity" to setOf("audit"),
        "cases" to setOf("audit", "common", "identity"),
        // KNOWN DEBT (context-map.md §Known debt): documents <-> processing <-> indexing form a cycle.
        "documents" to setOf("audit", "cases", "common", "config", "identity", "indexing", "processing", "storage"),
        "processing" to setOf("common", "config", "documents", "embeddings", "indexing", "storage"),
        // KNOWN DEBT: indexing -> search only for TurkishText (belongs in a shared text module).
        "indexing" to setOf("documents", "embeddings", "search"),
        "search" to setOf("cases", "common", "config", "embeddings", "identity"),
    )

    private val root: Path = Path.of("src/main/kotlin/tr/coldcase/api")
    private val importRe = Regex("""^import tr\.coldcase\.api\.([a-z]+)\.""", RegexOption.MULTILINE)

    private fun actualDependencies(): Map<String, Set<String>> {
        val deps = mutableMapOf<String, MutableSet<String>>()
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { file ->
                val rel = root.relativize(file)
                if (rel.nameCount < 2) return@forEach // top-level files (application class)
                val pkg = rel.getName(0).toString()
                val imports = importRe.findAll(file.readText()).map { it.groupValues[1] }.filter { it != pkg }
                deps.getOrPut(pkg) { mutableSetOf() }.addAll(imports)
            }
        }
        return deps
    }

    @Test
    fun `every package is declared in the context map`() {
        assertThat(actualDependencies().keys)
            .describedAs("New top-level package: add it to ContextBoundariesTest and docs/kb/domain/context-map.md")
            .isSubsetOf(allowed.keys)
    }

    @Test
    fun `cross-context imports match the context map exactly`() {
        val actual = actualDependencies()
        val problems = buildList {
            for ((pkg, deps) in actual) {
                val ok = allowed[pkg].orEmpty()
                (deps - ok).forEach { add("NEW dependency $pkg -> $it (record it in context-map.md first, or remove it)") }
                (ok - deps).forEach { add("STALE allowance $pkg -> $it (no longer used: remove it here and in context-map.md)") }
            }
        }
        assertThat(problems).describedAs(problems.joinToString("\n")).isEmpty()
    }
}

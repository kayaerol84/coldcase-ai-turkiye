plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
}

group = "tr.coldcase"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

// Security patch overrides of Spring Boot's managed versions (see docs/kb/security.md). Remove each when the
// Spring Boot BOM catches up, or with the Spring Boot 4 migration.
extra["tomcat.version"] = "10.1.60"
extra["jackson-bom.version"] = "2.21.7"
extra["postgresql.version"] = "42.7.12"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:s3") {
        // Only the synchronous client is used; leave out the Netty async stack (smaller attack surface).
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:apache-client")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") } }

// Exact resolved versions of what ships and what tests run on are committed (gradle.lockfile), so dependency
// scanners see exactly that. Build-tool internals (e.g. the Kotlin plugin's own classpaths) are not locked.
// Update with: ./gradlew dependencies --write-locks
val lockedConfigurations = setOf("compileClasspath", "runtimeClasspath", "productionRuntimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
configurations.matching { it.name in lockedConfigurations }.configureEach { resolutionStrategy.activateDependencyLocking() }

tasks.test {
    useJUnitPlatform { excludeTags("security") }
}

val securityTest by tasks.registering(Test::class) {
    description = "Security test suite (JUnit tag 'security'): authorization matrix, token validation, uploads, headers, log leakage."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("security") }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(securityTest) }

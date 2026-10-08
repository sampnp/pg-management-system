plugins {
    application
}

group = "com.pgmanager"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val vertxVersion = "5.2.1"
val flywayVersion = "13.10.0"
val mockitoVersion = "5.24.0"
val testcontainersVersion = "2.0.5"

// Mockito must be attached as a Java agent on JDK 21+ (dynamic agent loading is being phased out)
val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    // The Vert.x BOM keeps all Vert.x (and Jackson) module versions in sync
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")
    implementation("io.vertx:vertx-pg-client")
    implementation("io.vertx:vertx-auth-jwt")
    // Non-blocking Redis client (same Vert.x version via the BOM), used for the dashboard cache
    implementation("io.vertx:vertx-redis-client")

    implementation("at.favre.lib:bcrypt:0.10.2")

    // Lets Vert.x convert Java records/objects to and from JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // Adds JSON support for java.time types such as LocalDate (tenant joining date)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Flyway + JDBC are used ONLY to run migrations once at startup (on a worker thread).
    // All request-time database access goes through the non-blocking vertx-pg-client.
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    runtimeOnly("org.postgresql:postgresql:42.7.14")

    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    mockitoAgent("org.mockito:mockito-core:$mockitoVersion") { isTransitive = false }
    testImplementation("io.vertx:vertx-web-client")
    testImplementation("org.testcontainers:testcontainers-postgresql:$testcontainersVersion")
    // GenericContainer, used to start a throwaway Redis for the integration tests
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass = "com.pgmanager.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.named<JavaExec>("run") {
    // Load variables from .env (if present) so `./gradlew run` works without exporting them manually.
    // Real environment variables still work; .env is only a local-development convenience.
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}

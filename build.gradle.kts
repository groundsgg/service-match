plugins {
    id("gg.grounds.root") version "0.1.1"
    id("io.quarkus") version "3.30.6"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

// The contract is a moving SNAPSHOT, and Gradle caches changing modules for 24
// hours by default. On a warm CI cache that means a contract merged and
// published an hour ago is simply not seen: the build compiles against
// yesterday's proto and fails on a message that demonstrably exists. It cost a
// morning to find, because the artifact was right and the build was wrong.
configurations.all { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.30.8"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-kotlin")
    // Valkey (Redis wire protocol) — the queue spine. Every multi-key mutation
    // runs as a Lua script, because Valkey executes those atomically: that
    // serialisation is what makes double-booking a ticket impossible, without
    // a leader election or a distributed lock.
    implementation("io.quarkus:quarkus-redis-client")
    // Queue ticks. The band widens with waiting time, so the matcher is
    // inherently timer-driven — a purely event-driven loop could not widen.
    implementation("io.quarkus:quarkus-scheduler")
    // Agones lives in the same cluster we do, so allocation is a local call
    // against the in-cluster ServiceAccount — no mTLS to a foreign apiserver,
    // no aggregation-layer hop. (That whole apparatus was only needed while the
    // matchmaker was central.)
    implementation("io.quarkus:quarkus-kubernetes-client")
    // JWT validation for incoming gRPC calls. SDK attaches the
    // projected ServiceAccount token (aud=grounds-services); the
    // interceptor reads + verifies it against k8s JWKS.
    implementation("com.nimbusds:nimbus-jose-jwt:9.41.1")
    // OpenTelemetry — server-side gRPC instrumentation + OTLP exporter
    // to Alloy. Auto-wired via @WithSpan on @Blocking methods and the
    // built-in gRPC server interceptor.
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("gg.grounds:library-grpc-contracts-match:main-SNAPSHOT")
    // service-leaderboard is the writer target for a rated result's post-match
    // conservative skill; service-match is the only caller, forge never wires
    // this contract up on the gamemode's side.
    implementation("gg.grounds:library-grpc-contracts-leaderboard:main-SNAPSHOT")

    // Weng-Lin (OpenSkill) ratings. TrueSkill is the same family but is
    // patented until 2029-04-09 (WO2007094909A1) — not a risk worth taking
    // for a commercial platform. This lib is MIT and reproduces
    // openskill.py 6.2.0 to 10 decimal places, which is why it won over
    // the alternatives; RatingGoldenVectorTest pins that equivalence so a
    // silent upstream change cannot drift the ladder.
    implementation("io.github.toveri:openskill:1.0.0")

    compileOnly("com.google.protobuf:protobuf-kotlin")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.2.2")
    testImplementation("org.testcontainers:postgresql:1.21.5")
    testImplementation("org.testcontainers:junit-jupiter:1.21.5")
}

sourceSets { main { java { srcDirs("build/classes/java/quarkus-generated-sources/grpc") } } }

tasks.test {
    // Testcontainers' docker-java still negotiates API 1.32 by default, and a
    // modern daemon refuses that outright ("client version 1.32 is too old.
    // Minimum supported API version is 1.40"). docker-java takes the version
    // from this system property. Inherit the caller's value where one is set,
    // so CI can override.
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.44")
}

tasks
    .matching { it.name == "kaptGenerateStubsKotlin" }
    .configureEach {
        dependsOn("quarkusGenerateCode")
        dependsOn("quarkusGenerateCodeDev")
    }

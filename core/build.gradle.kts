plugins {
    kotlin("jvm")
}

dependencies {
    // ByteBuffer utilities
    implementation("io.netty:netty-buffer:4.1.104.Final")

    // PostgreSQL JDBC driver (E2E 테스트용)
    testImplementation("org.postgresql:postgresql:42.7.1")
}

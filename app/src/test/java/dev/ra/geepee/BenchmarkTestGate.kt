package dev.ra.geepee

import org.junit.Assume.assumeTrue

private const val RUN_BENCHMARKS_PROPERTY = "geepee.runBenchmarks"
private const val RUN_BENCHMARKS_ENV = "GEEPEE_RUN_BENCHMARKS"

internal fun requireBenchmarkOptIn() {
    val propertyEnabled = System.getProperty(RUN_BENCHMARKS_PROPERTY)?.toBooleanStrictOrNull() == true
    val envEnabled = System.getenv(RUN_BENCHMARKS_ENV)?.toBooleanStrictOrNull() == true
    assumeTrue(
        "Benchmark tests are opt-in. Set -D$RUN_BENCHMARKS_PROPERTY=true or $RUN_BENCHMARKS_ENV=true to run them.",
        propertyEnabled || envEnabled,
    )
}

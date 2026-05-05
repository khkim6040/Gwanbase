package gwanbase

import gwanbase.kv.KVStore
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * B+Tree 삽입/검색 벤치마크.
 *
 * 디스크 기반 B+Tree(KVStore)의 단일 스레드 throughput을 측정한다.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class BPlusTreeBenchmark {

    private lateinit var tempDir: Path
    private lateinit var kvStore: KVStore
    private var keyCounter = 0

    @Param("100", "1000", "10000")
    open var preloadCount: Int = 0

    @Setup(Level.Trial)
    fun setup() {
        tempDir = Files.createTempDirectory("bench-bptree")
        kvStore = KVStore.open(tempDir.resolve("bench.db"), bufferPoolSize = 1024)

        for (i in 1..preloadCount) {
            val key = "key-${i.toString().padStart(8, '0')}".toByteArray()
            val value = "value-$i".toByteArray()
            kvStore.put(key, value)
        }
        keyCounter = preloadCount
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        kvStore.close()
        tempDir.toFile().deleteRecursively()
    }

    @Benchmark
    fun insert(): ByteArray {
        keyCounter++
        val key = "key-${keyCounter.toString().padStart(8, '0')}".toByteArray()
        val value = "value-$keyCounter".toByteArray()
        kvStore.put(key, value)
        return key
    }

    @Benchmark
    fun pointLookupExisting(): ByteArray? {
        val idx = (1..preloadCount).random()
        val key = "key-${idx.toString().padStart(8, '0')}".toByteArray()
        return kvStore.get(key)
    }

    @Benchmark
    fun pointLookupMissing(): ByteArray? {
        return kvStore.get("nonexistent-key-999999".toByteArray())
    }
}

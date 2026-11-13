package org.logstash.plugins.inputs.escompat.bench;

import com.fasterxml.jackson.core.JsonFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.logstash.plugins.inputs.escompat.bulk.BulkParser;
import org.logstash.plugins.inputs.escompat.bulk.ItemOutcome;
import org.logstash.plugins.inputs.escompat.bulk.Operation;
import org.logstash.plugins.inputs.escompat.response.BulkResponseWriter;
import org.logstash.plugins.inputs.escompat.response.FilterPath;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SC-007, Java part: parse + outcome bookkeeping + response write for one request, using real
 * exporter documents (captured by CaptureExporterBodiesIT into {@code target/bench/exporter})
 * resized to 1,000 / 5,000 / 10,000 items. Excludes Netty I/O and Ruby event building (measured
 * separately by event_build_bench.rb).
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
@State(Scope.Benchmark)
public class BulkPathBenchmark {
    private static final FilterPath EXPORTER_FILTER = FilterPath.parse(
        "items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason");

    @Param({"1000", "5000", "10000"})
    public int items;

    private byte[] body;
    private BulkParser parser;
    private BulkResponseWriter writer;

    @Setup
    public void setup() throws Exception {
        final Path dir = Path.of(System.getProperty("bench.dir", "target/bench/exporter"));
        final String[] lines = new String(Files.readAllBytes(dir.resolve("000.ndjson")), StandardCharsets.UTF_8)
            .split("\n");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < items; i++) {
            final int pair = (i % (lines.length / 2)) * 2;
            out.write((lines[pair] + "\n" + lines[pair + 1] + "\n").getBytes(StandardCharsets.UTF_8));
        }
        body = out.toByteArray();
        final JsonFactory json = new JsonFactory();
        parser = new BulkParser(json);
        writer = new BulkResponseWriter(json);
    }

    @Benchmark
    public int request() throws Exception {
        final List<Operation> ops = parser.parse(body, 0, body.length);
        final ItemOutcome[] outcomes = new ItemOutcome[ops.size()];
        Arrays.fill(outcomes, ItemOutcome.QUEUED); // no-op handler
        final ByteBuf res = writer.write(ops, outcomes, 1, EXPORTER_FILTER, PooledByteBufAllocator.DEFAULT);
        final int n = res.readableBytes();
        res.release();
        return n;
    }
}

# encoding: utf-8
# SC-007 inside Logstash's own Ruby (T048b). Per request size, prints:
#   ruby:     event building only — Event.new(java Map) + event.set(metadata) + tag/add_field
#   combined: the whole plugin-added path for one request — parse + event building + response
#             write (everything except Netty I/O and waiting on the queue)
# Usage: LS_HOME/bin/ruby -I logstash-core/lib event_build_bench.rb <plugin.jar> <bodies dir>
require "logstash-core/logstash-core"
require "java"
require ARGV[0]

SRV = Java::OrgLogstashPluginsInputsEscompatServer
BULK = Java::OrgLogstashPluginsInputsEscompatBulk
RESP = Java::OrgLogstashPluginsInputsEscompatResponse
json = SRV::Responses::JSON # the plugin's shaded Jackson factory
parser = BULK::BulkParser.new(json)
writer = RESP::BulkResponseWriter.new(json)
filter = RESP::FilterPath.parse(
  "items.*._index,items.*.status,items.*.failure_store,items.*.error.type,items.*.error.reason")
alloc = Java::OrgLogstashPluginsInputsEscompatShadedNettyBuffer::PooledByteBufAllocator::DEFAULT
queued = BULK::ItemOutcome::QUEUED
lines = File.read(File.join(ARGV[1], "000.ndjson")).split("\n")

def percentile(sorted, p)
  sorted[[(sorted.size * p).ceil - 1, 0].max]
end

def build_events(ops)
  n = ops.size
  i = 0
  while i < n
    op = ops.get(i)
    event = LogStash::Event.new(op.document)
    event.set("[@metadata][elasticsearch_compat]", op.metadata)
    event.tag("t1")
    event.set("f1", "v1")
    i += 1
  end
end

[1000, 5000, 10000].each do |items|
  body = (0...items).map { |i| pair = (i % (lines.size / 2)) * 2; "#{lines[pair]}\n#{lines[pair + 1]}\n" }.join
  bytes = body.to_java_bytes
  ruby = []
  combined = []
  iterations = items == 1000 ? 300 : 60
  (iterations + 30).times do |iter|
    ops = parser.parse(bytes, 0, bytes.length)
    t0 = java.lang.System.nano_time
    build_events(ops)
    ruby << (java.lang.System.nano_time - t0) / 1e6 if iter >= 30

    t1 = java.lang.System.nano_time
    ops2 = parser.parse(bytes, 0, bytes.length)
    build_events(ops2)
    outcomes = BULK::ItemOutcome[ops2.size].new
    java.util.Arrays.fill(outcomes, queued)
    writer.write(ops2, outcomes, 1, filter, alloc).release
    combined << (java.lang.System.nano_time - t1) / 1e6 if iter >= 30
  end
  r = ruby.sort
  c = combined.sort
  printf("ruby     items=%d p50=%.2fms p99=%.2fms\n", items, percentile(r, 0.50), percentile(r, 0.99))
  printf("combined items=%d p50=%.2fms p99=%.2fms\n", items, percentile(c, 0.50), percentile(c, 0.99))
end

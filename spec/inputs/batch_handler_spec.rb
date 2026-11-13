# encoding: utf-8
# Runs on plain JRuby (no Logstash) after `mvn package`:
#   jruby -S rspec spec/inputs/batch_handler_spec.rb
require "java"

jar = Dir[File.expand_path("../../target/logstash-input-elasticsearch_compat-*.jar", __dir__)].first
raise "shaded JAR not found; run `mvn package` first" unless jar
require jar
require_relative "../../lib/logstash/inputs/elasticsearch_compat/batch_handler"

describe LogStash::ElasticsearchCompat::BatchHandler do
  Bulk = Java::OrgLogstashPluginsInputsEscompatBulk
  ItemOutcome = Bulk::ItemOutcome

  class FakeLogger
    attr_reader :errors
    def initialize; @errors = []; end
    def error(msg, data = {}); @errors << [msg, data]; end
  end

  let(:logger) { FakeLogger.new }
  let(:pushed) { [] }

  def ops(n)
    list = java.util.ArrayList.new
    n.times do |i|
      doc = java.util.HashMap.new
      doc.put("n", i)
      doc.put("secret", "do-not-log-#{i}")
      list.add(Bulk::Operation.new(Bulk::Action::INDEX, "idx", "id#{i}", nil, nil, doc, nil))
    end
    list
  end

  def results(n)
    ItemOutcome[n].new
  end

  it "queues every op in order" do
    sink = ->(doc, meta) { pushed << [doc.get("n"), meta.get("id")] }
    handler = described_class.new(sink, logger)
    r = results(3)
    handler.submit(ops(3), r)
    expect(r.to_a).to eq([ItemOutcome::QUEUED] * 3)
    expect(pushed).to eq([[0, "id0"], [1, "id1"], [2, "id2"]])
  end

  it "marks the failing op SUBMIT_FAILED and leaves the rest not attempted" do
    sink = ->(doc, _meta) { raise "queue closed" if doc.get("n") == 1; pushed << doc.get("n") }
    handler = described_class.new(sink, logger)
    r = results(4)
    handler.submit(ops(4), r)
    expect(r.to_a).to eq([ItemOutcome::QUEUED, ItemOutcome::SUBMIT_FAILED, nil, nil])
    expect(pushed).to eq([0])
  end

  it "treats Java exceptions the same way" do
    sink = ->(_doc, _meta) { raise java.lang.IllegalStateException.new("boom") }
    handler = described_class.new(sink, logger)
    r = results(2)
    handler.submit(ops(2), r)
    expect(r.to_a).to eq([ItemOutcome::SUBMIT_FAILED, nil])
  end

  it "never logs the document" do
    sink = ->(_doc, _meta) { raise "boom" }
    handler = described_class.new(sink, logger)
    handler.submit(ops(1), results(1))
    expect(logger.errors.size).to eq(1)
    expect(logger.errors.inspect).not_to include("do-not-log")
  end

  it "attempts nothing after stop!" do
    handler = described_class.new(->(doc, _m) { pushed << doc }, logger)
    handler.stop!
    r = results(2)
    handler.submit(ops(2), r)
    expect(r.to_a).to eq([nil, nil])
    expect(pushed).to be_empty
  end
end

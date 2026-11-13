# encoding: utf-8
# Plain JRuby after `mvn package`: jruby -S rspec spec/inputs/metrics_publisher_spec.rb
require "java"

jar = Dir[File.expand_path("../../target/logstash-input-elasticsearch_compat-*.jar", __dir__)].first
raise "shaded JAR not found; run `mvn package` first" unless jar
require jar
require_relative "../../lib/logstash/inputs/elasticsearch_compat/metrics_publisher"

describe LogStash::ElasticsearchCompat::MetricsPublisher do
  class FakeMetric
    attr_reader :gauges
    def initialize; @gauges = {}; @lock = Mutex.new; end
    def gauge(key, value); @lock.synchronize { @gauges[key] = value }; end
  end

  let(:metric) { FakeMetric.new }
  let(:counters) { Java::OrgLogstashPluginsInputsEscompatServer::RequestCounters.new }
  let(:keys) do
    %i[bulk_requests bulk_requests_rejected_overload bulk_requests_rejected_too_large
       bulk_requests_rejected_unsupported_encoding bulk_requests_rejected_bad_request
       bulk_items_failed queue_submit_failures]
  end

  it "publishes every counter by its contract name" do
    counters.bulk_request
    counters.bulk_request
    counters.rejected_overload
    counters.items_failed(3)
    described_class.new(metric, counters).publish
    expect(metric.gauges.keys).to match_array(keys)
    expect(metric.gauges[:bulk_requests]).to eq(2)
    expect(metric.gauges[:bulk_requests_rejected_overload]).to eq(1)
    expect(metric.gauges[:bulk_items_failed]).to eq(3)
  end

  it "publishes periodically and stops cleanly" do
    publisher = described_class.new(metric, counters)
    publisher.start
    counters.rejected_too_large
    sleep 1.5
    expect(metric.gauges[:bulk_requests_rejected_too_large]).to eq(1)
    publisher.stop
  end
end

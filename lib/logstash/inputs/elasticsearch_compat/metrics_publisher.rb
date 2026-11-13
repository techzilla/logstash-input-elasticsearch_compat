# encoding: utf-8

module LogStash
  module ElasticsearchCompat
    # Publishes the Java RequestCounters into this input's Logstash metrics as gauges of
    # cumulative values (research R6): after each request that reaches Ruby, and every second so
    # refused requests (which never reach Ruby) still show up. Gauges are idempotent, so
    # concurrent publishes need no locking. With enable_metric => false, `metric` is Logstash's
    # null metric and nothing is reported.
    class MetricsPublisher
      INTERVAL_SECONDS = 1

      # @param metric [#gauge] the plugin's namespaced metric
      # @param counters [#snapshot] Java RequestCounters
      def initialize(metric, counters)
        @metric = metric
        @counters = counters
        @thread = nil
        @running = java.util.concurrent.atomic.AtomicBoolean.new(false)
      end

      def publish
        @counters.snapshot.each { |key, value| @metric.gauge(key.to_sym, value) }
      end

      def start
        return unless @running.compare_and_set(false, true)
        @thread = Thread.new do
          Thread.current.name = "escompat-metrics" if Thread.current.respond_to?(:name=)
          while @running.get
            begin
              publish
            rescue StandardError, java.lang.Exception
              # metrics must never affect ingestion
            end
            sleep INTERVAL_SECONDS
          end
        end
      end

      def stop
        @running.set(false)
        thread = @thread
        thread.wakeup rescue nil if thread
        thread.join(5) if thread
        publish rescue nil
      end
    end
  end
end

# encoding: utf-8
require "java"

module LogStash
  module ElasticsearchCompat
    # Implements the Java BatchHandler: called once per request from a worker thread.
    # Pushes one event per operation, in order, and records each known outcome.
    # Entries left nil are reported by Java as NOT_ATTEMPTED (503, safe to retry).
    class BatchHandler
      include Java::OrgLogstashPluginsInputsEscompat::BatchHandler

      ItemOutcome = Java::OrgLogstashPluginsInputsEscompatBulk::ItemOutcome
      QUEUED = ItemOutcome::QUEUED
      SUBMIT_FAILED = ItemOutcome::SUBMIT_FAILED

      # @param sink [#call] called with (document, metadata); builds and queues one event.
      #   Raising means the outcome is unknown.
      # @param logger [#error]
      def initialize(sink, logger)
        @sink = sink
        @logger = logger
        @stopping = java.util.concurrent.atomic.AtomicBoolean.new(false)
      end

      # After this, operations not yet started are left NOT_ATTEMPTED.
      def stop!
        @stopping.set(true)
      end

      def submit(ops, results)
        n = ops.size
        i = 0
        while i < n
          return if @stopping.get
          op = ops.get(i)
          begin
            @sink.call(op.document, op.metadata)
          rescue StandardError, java.lang.Exception => e
            results[i] = SUBMIT_FAILED
            # Never log the document (constitution VI).
            @logger.error("elasticsearch_compat: failed to queue event; remaining items in the " \
                          "request were not attempted", :exception => e.class.name, :message => e.message)
            return
          end
          results[i] = QUEUED
          i += 1
        end
        nil
      end
    end
  end
end

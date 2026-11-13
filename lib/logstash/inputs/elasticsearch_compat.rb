# encoding: utf-8
require "logstash/inputs/base"
require "logstash/namespace"
require "logstash-input-elasticsearch_compat_jars"
require "logstash/inputs/elasticsearch_compat/batch_handler"

# Receives Elasticsearch Bulk API requests (POST /_bulk) and emits one event per
# index/create action. Items are acknowledged only after the event is accepted by
# the Logstash queue. update/delete get item-level errors. Credentials are accepted
# and ignored.
class LogStash::Inputs::ElasticsearchCompat < LogStash::Inputs::Base
  config_name "elasticsearch_compat"

  # Bulk bodies are parsed by the Java backend, not by a codec.
  default :codec, "plain"

  # Listen address.
  config :host, :validate => :string, :default => "0.0.0.0"

  # Listen port.
  config :port, :validate => :number, :default => 9200

  # Worker threads that parse requests and push events.
  config :threads, :validate => :number, :default => ::LogStash::Config::CpuCoreStrategy.maximum

  # Requests waiting for a worker; beyond this, new requests get 429.
  config :max_pending_requests, :validate => :number, :default => 200

  # Maximum request body size in bytes; larger bodies get 413.
  config :max_content_length, :validate => :number, :default => 100 * 1024 * 1024

  # Where routing metadata (action, index, id, routing, pipeline) is stored.
  config :metadata_target, :validate => :string, :default => "[@metadata][elasticsearch_compat]"

  def register
    [[:threads, @threads], [:max_pending_requests, @max_pending_requests],
     [:max_content_length, @max_content_length]].each do |name, value|
      raise LogStash::ConfigurationError, "#{name} must be > 0" unless value > 0
    end
    if @max_content_length > java.lang.Integer::MAX_VALUE
      raise LogStash::ConfigurationError, "max_content_length must be <= #{java.lang.Integer::MAX_VALUE}"
    end

    @batch_handler = LogStash::ElasticsearchCompat::BatchHandler.new(method(:push_event), logger)
    @server = Java::OrgLogstashPluginsInputsEscompatServer::EsCompatServer.new(
      "escompat-#{id}", @host, @port, @threads, @max_pending_requests, @max_content_length,
      @batch_handler)
  end

  def run(queue)
    @queue = queue
    logger.info("Starting elasticsearch_compat input", :address => "#{@host}:#{@port}")
    @server.run
  end

  def stop
    @batch_handler.stop! if @batch_handler
    @server.close if @server
  rescue => e
    logger.debug("error while stopping elasticsearch_compat input", :exception => e.class.name)
  end

  def close
    @server.close if @server
  rescue => e
    logger.debug("error while closing elasticsearch_compat input", :exception => e.class.name)
  end

  private

  # Called from Java worker threads via the batch handler.
  def push_event(document, metadata)
    event = LogStash::Event.new(document)
    event.set(@metadata_target, metadata)
    decorate(event)
    @queue << event
  end
end

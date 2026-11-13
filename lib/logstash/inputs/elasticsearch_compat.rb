# encoding: utf-8
require "logstash/inputs/base"
require "logstash/namespace"
require "logstash-input-elasticsearch_compat_jars"
require "logstash/inputs/elasticsearch_compat/batch_handler"
require "logstash/inputs/elasticsearch_compat/ssl_validation"
require "logstash/inputs/elasticsearch_compat/metrics_publisher"

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

  # Compress responses when the client accepts gzip or deflate (level 3, like Elasticsearch's
  # http.compression). Compressed request bodies are always accepted.
  config :compression_enabled, :validate => :boolean, :default => true

  # TLS: same settings, defaults and rules as logstash-input-http.
  config :ssl_enabled, :validate => :boolean, :default => false
  config :ssl_certificate, :validate => :path
  config :ssl_key, :validate => :path
  config :ssl_key_passphrase, :validate => :password
  config :ssl_keystore_path, :validate => :path
  config :ssl_keystore_password, :validate => :password
  config :ssl_keystore_type, :validate => %w[pkcs12 jks]
  config :ssl_certificate_authorities, :validate => :array, :default => []
  config :ssl_truststore_path, :validate => :path
  config :ssl_truststore_password, :validate => :password
  config :ssl_client_authentication, :validate => %w[none optional required], :default => "none"
  config :ssl_supported_protocols, :validate => %w[TLSv1.1 TLSv1.2 TLSv1.3], :default => %w[TLSv1.2 TLSv1.3],
         :list => true
  config :ssl_cipher_suites,
         :validate => Java::OrgLogstashPluginsInputsEscompatServerTls::SslContextFactory.supportedCiphers.to_a,
         :default => Java::OrgLogstashPluginsInputsEscompatServerTls::SslContextFactory.defaultCiphers.to_a,
         :list => true
  config :ssl_handshake_timeout, :validate => :number, :default => 10000

  def register
    [[:threads, @threads], [:max_pending_requests, @max_pending_requests],
     [:max_content_length, @max_content_length]].each do |name, value|
      raise LogStash::ConfigurationError, "#{name} must be > 0" unless value > 0
    end
    if @max_content_length > java.lang.Integer::MAX_VALUE
      raise LogStash::ConfigurationError, "max_content_length must be <= #{java.lang.Integer::MAX_VALUE}"
    end

    raise LogStash::ConfigurationError, "ssl_handshake_timeout must be > 0" unless @ssl_handshake_timeout > 0

    counters = Java::OrgLogstashPluginsInputsEscompatServer::RequestCounters.new
    @metrics_publisher = LogStash::ElasticsearchCompat::MetricsPublisher.new(metric, counters)
    @batch_handler = LogStash::ElasticsearchCompat::BatchHandler.new(
      method(:push_event), logger, @metrics_publisher.method(:publish))

    config = Java::OrgLogstashPluginsInputsEscompat::ServerConfig.builder
      .name("escompat-#{id}").host(@host).port(@port).threads(@threads)
      .maxPendingRequests(@max_pending_requests).maxContentLength(@max_content_length)
      .compressionEnabled(@compression_enabled).counters(counters)
      .sslContext(build_ssl_context).sslHandshakeTimeoutMillis(@ssl_handshake_timeout)
      .build
    @server = Java::OrgLogstashPluginsInputsEscompatServer::EsCompatServer.new(config, @batch_handler)
  end

  def run(queue)
    @queue = queue
    logger.info("Starting elasticsearch_compat input", :address => "#{@host}:#{@port}", :ssl_enabled => @ssl_enabled)
    @metrics_publisher.start
    @server.run
  end

  def stop
    @batch_handler.stop! if @batch_handler
    @server.close if @server
    @metrics_publisher.stop if @metrics_publisher
  rescue => e
    logger.debug("error while stopping elasticsearch_compat input", :exception => e.class.name)
  end

  def close
    @server.close if @server
    @metrics_publisher.stop if @metrics_publisher
  rescue => e
    logger.debug("error while closing elasticsearch_compat input", :exception => e.class.name)
  end

  private

  # Validates like logstash-input-http, then builds the SslContext (nil when TLS is off).
  def build_ssl_context
    settings = {
      "ssl_enabled" => @ssl_enabled, "ssl_certificate" => @ssl_certificate, "ssl_key" => @ssl_key,
      "ssl_keystore_path" => @ssl_keystore_path, "ssl_truststore_path" => @ssl_truststore_path,
      "ssl_certificate_authorities" => @ssl_certificate_authorities,
      "ssl_client_authentication" => @ssl_client_authentication
    }
    explicit = original_params.keys.select { |k| k.start_with?("ssl_") }
    result = LogStash::ElasticsearchCompat::SslValidation.check(settings, explicit)
    result[:warnings].each { |w| logger.warn(w) }
    raise LogStash::ConfigurationError, result[:errors].first if result[:errors].any?
    return nil unless @ssl_enabled

    tls = Java::OrgLogstashPluginsInputsEscompatServerTls::TlsSettings.new(
      @ssl_certificate, @ssl_key, secret(@ssl_key_passphrase),
      @ssl_keystore_path, secret(@ssl_keystore_password), @ssl_keystore_type,
      @ssl_certificate_authorities, @ssl_truststore_path, secret(@ssl_truststore_password),
      @ssl_client_authentication, @ssl_supported_protocols, @ssl_cipher_suites)
    Java::OrgLogstashPluginsInputsEscompatServerTls::SslContextFactory.build(tls)
  rescue java.lang.IllegalArgumentException => e
    logger.error("SSL configuration invalid", :message => e.message)
    raise LogStash::ConfigurationError, e.message
  rescue java.lang.Exception => e
    logger.error("SSL configuration failed", :exception => e.class.name, :message => e.message)
    raise LogStash::ConfigurationError, e.message
  end

  def secret(password)
    password.nil? ? nil : password.value
  end

  # Called from Java worker threads via the batch handler.
  def push_event(document, metadata)
    event = LogStash::Event.new(document)
    event.set(@metadata_target, metadata)
    decorate(event)
    @queue << event
  end
end

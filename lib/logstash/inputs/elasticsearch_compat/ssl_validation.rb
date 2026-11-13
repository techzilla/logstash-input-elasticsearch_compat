# encoding: utf-8

module LogStash
  module ElasticsearchCompat
    # TLS setting rules identical to logstash-input-http's validate_ssl_settings!, including its
    # messages (contracts/plugin-config.md rules 1-6). Pure: no Logstash dependency, so it can be
    # specified with plain JRuby.
    module SslValidation
      OPTIONAL = "optional".freeze
      REQUIRED = "required".freeze

      # @param s [Hash] setting name (String) => value, as configured
      # @param explicit [Array<String>] names the user actually set (for the warning)
      # @return [Hash] { errors: [String] (first rule only, as logstash-input-http raises), warnings: [String] }
      def self.check(s, explicit)
        unless s["ssl_enabled"]
          ignored = explicit.select { |k| k != "ssl_enabled" && k.start_with?("ssl_") }
          warnings = ignored.empty? ? [] :
            ["Configured SSL settings are not used when `ssl_enabled` is set to `false`: #{ignored}"]
          return { errors: [], warnings: warnings }
        end
        { errors: Array(first_error(s)), warnings: [] }
      end

      def self.first_error(s)
        cert = present?(s["ssl_certificate"])
        key = present?(s["ssl_key"])
        keystore = present?(s["ssl_keystore_path"])
        truststore = present?(s["ssl_truststore_path"])
        cas = Array(s["ssl_certificate_authorities"]).any?
        client_auth = s["ssl_client_authentication"].to_s.downcase

        return "Using an `ssl_certificate` requires an `ssl_key`" if cert && !key
        return "An `ssl_certificate` is required when using an `ssl_key`" if key && !cert
        unless cert || keystore
          return "Either an `ssl_certificate` or `ssl_keystore_path` is required when SSL is enabled `ssl_enabled => true`"
        end
        return "Use either an `ssl_certificate` or an `ssl_keystore_path`" if cert && keystore
        return "Use either an `ssl_certificate_authorities` or an `ssl_truststore_path`" if cas && truststore

        if client_auth == OPTIONAL || client_auth == REQUIRED
          unless cas || truststore || keystore
            return "Using `ssl_client_authentication` set to `#{OPTIONAL}` or `#{REQUIRED}`, requires the " \
                   "configuration of `ssl_certificate_authorities` or `ssl_truststore_path`"
          end
        else
          if truststore
            return "The configuration of `ssl_truststore_path` requires setting `ssl_client_authentication` " \
                   "to `#{OPTIONAL}` or '#{REQUIRED}'"
          end
          if cas
            return "The configuration of `ssl_certificate_authorities` requires setting " \
                   "`ssl_client_authentication` to `#{OPTIONAL}` or '#{REQUIRED}'"
          end
        end
        nil
      end

      def self.present?(v)
        !(v.nil? || v.to_s.empty?)
      end
    end
  end
end

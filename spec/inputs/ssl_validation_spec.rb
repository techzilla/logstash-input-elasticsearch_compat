# encoding: utf-8
# Plain JRuby: jruby -S rspec spec/inputs/ssl_validation_spec.rb
require_relative "../../lib/logstash/inputs/elasticsearch_compat/ssl_validation"

describe LogStash::ElasticsearchCompat::SslValidation do
  def check(settings)
    s = { "ssl_enabled" => true, "ssl_client_authentication" => "none", "ssl_certificate_authorities" => [] }
          .merge(settings)
    described_class.check(s, settings.keys)
  end

  def error(settings)
    check(settings)[:errors].first
  end

  context "valid combinations" do
    it("certificate + key") { expect(error("ssl_certificate" => "c", "ssl_key" => "k")).to be_nil }
    it("keystore") { expect(error("ssl_keystore_path" => "ks")).to be_nil }
    it "client auth with CAs" do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_client_authentication" => "required",
                   "ssl_certificate_authorities" => ["ca"])).to be_nil
    end
    it "client auth with truststore" do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_client_authentication" => "optional",
                   "ssl_truststore_path" => "ts")).to be_nil
    end
    it "client auth with keystore as trust" do
      expect(error("ssl_keystore_path" => "ks", "ssl_client_authentication" => "required")).to be_nil
    end
  end

  context "rule 1" do
    it { expect(error("ssl_certificate" => "c")).to eq("Using an `ssl_certificate` requires an `ssl_key`") }
    it { expect(error("ssl_key" => "k")).to eq("An `ssl_certificate` is required when using an `ssl_key`") }
  end

  context "rule 2" do
    it "needs certificate or keystore" do
      expect(error({})).to eq("Either an `ssl_certificate` or `ssl_keystore_path` is required when SSL is enabled `ssl_enabled => true`")
    end
    it "not both" do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_keystore_path" => "ks"))
        .to eq("Use either an `ssl_certificate` or an `ssl_keystore_path`")
    end
  end

  context "rule 3" do
    it do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_client_authentication" => "required",
                   "ssl_certificate_authorities" => ["ca"], "ssl_truststore_path" => "ts"))
        .to eq("Use either an `ssl_certificate_authorities` or an `ssl_truststore_path`")
    end
  end

  context "rule 4" do
    it do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_client_authentication" => "required"))
        .to eq("Using `ssl_client_authentication` set to `optional` or `required`, requires the configuration of `ssl_certificate_authorities` or `ssl_truststore_path`")
    end
  end

  context "rule 5" do
    it "truststore without client auth" do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_truststore_path" => "ts"))
        .to eq("The configuration of `ssl_truststore_path` requires setting `ssl_client_authentication` to `optional` or 'required'")
    end
    it "CAs without client auth" do
      expect(error("ssl_certificate" => "c", "ssl_key" => "k", "ssl_certificate_authorities" => ["ca"]))
        .to eq("The configuration of `ssl_certificate_authorities` requires setting `ssl_client_authentication` to `optional` or 'required'")
    end
  end

  context "rule 6" do
    it "warns, never errors, when TLS is off" do
      result = described_class.check({ "ssl_enabled" => false, "ssl_certificate" => "c" },
                                     ["ssl_certificate"])
      expect(result[:errors]).to be_empty
      expect(result[:warnings].first).to start_with("Configured SSL settings are not used when `ssl_enabled` is set to `false`")
    end
    it("no warning without ssl settings") do
      expect(described_class.check({ "ssl_enabled" => false }, [])[:warnings]).to be_empty
    end
  end
end

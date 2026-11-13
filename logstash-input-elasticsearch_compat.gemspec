Gem::Specification.new do |s|
  s.name          = "logstash-input-elasticsearch_compat"
  s.version       = File.read(File.expand_path("VERSION", __dir__)).strip
  s.licenses      = ["Apache-2.0"]
  s.summary       = "Receives Elasticsearch Bulk API requests and emits Logstash events"
  s.description   = "This gem is a Logstash plugin required to be installed on top of the " \
                    "Logstash core pipeline using $LS_HOME/bin/logstash-plugin install gemname. " \
                    "This gem is not a stand-alone program."
  s.authors       = ["J. M. Becker"]
  s.require_paths = ["lib", "vendor/jar-dependencies"]
  s.platform      = "java"

  s.files = Dir["lib/**/*", "vendor/jar-dependencies/**/*.jar", "*.gemspec", "*.md", "VERSION",
                "LICENSE", "NOTICE.TXT"]

  s.metadata = { "logstash_plugin" => "true", "logstash_group" => "input" }

  s.add_runtime_dependency "logstash-core-plugin-api", ">= 1.60", "<= 2.99"
  s.add_runtime_dependency "logstash-codec-plain"

  s.add_development_dependency "rspec", "~> 3.13"
end

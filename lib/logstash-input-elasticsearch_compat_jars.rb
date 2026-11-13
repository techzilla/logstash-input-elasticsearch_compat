# encoding: utf-8
require "jar_dependencies"

require_jar("org.logstash.plugins.inputs", "logstash-input-elasticsearch_compat",
            File.read(File.expand_path("../VERSION", __dir__)).strip)

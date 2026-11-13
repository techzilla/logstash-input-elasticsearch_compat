package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;
import org.logstash.plugins.inputs.escompat.it.support.Http;
import org.logstash.plugins.inputs.escompat.it.support.LogstashContainer;
import org.testcontainers.containers.Network;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T021: the gem installs, the JAR loads, and POST /_bulk answers in real Logstash. */
class SmokeIT {
    @Test
    void pluginAnswersBulk() throws Exception {
        try (Network net = Network.newNetwork();
             LogstashContainer ls = new LogstashContainer("capture.conf", "memory", net)) {
            ls.start();
            final Http.Response r = Http.post(ls.bulkUri(9200), Http.op("index", "a", "{}"));
            assertEquals(200, r.status());
            assertEquals("Elasticsearch", r.product());
        }
    }
}

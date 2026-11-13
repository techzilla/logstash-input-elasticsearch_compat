package org.logstash.plugins.inputs.escompat.it;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** T039 (US5, SC-007): license/notice in the JAR and gem; docs cover every setting. */
class PackagingIT {
    private static final Path JAR = Path.of("target/logstash-input-elasticsearch_compat-"
        + System.getProperty("project.version", "0.1.0") + ".jar");

    @Test
    void jarCarriesLicenseAndAllNotices() throws Exception {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            assertTrue(zip.getEntry("META-INF/LICENSE") != null);
            final String notice = new String(zip.getInputStream(zip.getEntry("META-INF/NOTICE")).readAllBytes(),
                StandardCharsets.UTF_8);
            for (final String s : new String[] {"The Netty Project", "Jackson", "logstash-input-http"}) {
                assertTrue(notice.contains(s), "NOTICE lacks " + s);
            }
        }
    }

    @Test
    void gemContainsReleaseFiles() throws Exception {
        final List<String> names = gemEntries(Path.of(System.getProperty("plugin.gem",
            "target/logstash-input-elasticsearch_compat.gem")));
        for (final String f : new String[] {"LICENSE", "NOTICE.TXT", "CHANGELOG.md", "docs/index.asciidoc"}) {
            assertTrue(names.contains(f), "gem lacks " + f + ": " + names);
        }
    }

    @Test
    void docsCoverEverySetting() throws Exception {
        final String ruby = Files.readString(Path.of("lib/logstash/inputs/elasticsearch_compat.rb"));
        final String docs = Files.readString(Path.of("docs/index.asciidoc"));
        final Matcher m = Pattern.compile("(?m)^\\s*config :([a-z_]+)").matcher(ruby);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(docs.contains("plugin}-" + m.group(1) + "\"]"), "docs lack setting " + m.group(1));
        }
        assertTrue(n >= 21, "expected all settings, found " + n);
    }

    /** Lists file names inside a .gem (a tar containing data.tar.gz). */
    private static List<String> gemEntries(final Path gem) throws Exception {
        final byte[] tar = Files.readAllBytes(gem);
        final byte[] data = tarEntry(tar, "data.tar.gz");
        return tarNames(new GZIPInputStream(new ByteArrayInputStream(data)).readAllBytes());
    }

    private static byte[] tarEntry(final byte[] tar, final String name) {
        for (int off = 0; off + 512 <= tar.length; ) {
            final String n = new String(tar, off, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
            if (n.isEmpty()) break;
            final int size = Integer.parseInt(new String(tar, off + 124, 12, StandardCharsets.US_ASCII).trim().replace("\0", ""), 8);
            if (n.equals(name)) return java.util.Arrays.copyOfRange(tar, off + 512, off + 512 + size);
            off += 512 + ((size + 511) / 512) * 512;
        }
        throw new IllegalStateException(name + " not in gem");
    }

    private static List<String> tarNames(final byte[] tar) {
        final List<String> names = new ArrayList<>();
        for (int off = 0; off + 512 <= tar.length; ) {
            final String n = new String(tar, off, 100, StandardCharsets.US_ASCII).replace("\0", "").trim();
            if (n.isEmpty()) break;
            final String prefix = new String(tar, off + 345, 155, StandardCharsets.US_ASCII).replace("\0", "").trim();
            names.add(prefix.isEmpty() ? n : prefix + "/" + n);
            final int size = Integer.parseInt(new String(tar, off + 124, 12, StandardCharsets.US_ASCII).trim().replace("\0", ""), 8);
            off += 512 + ((size + 511) / 512) * 512;
        }
        return names;
    }
}

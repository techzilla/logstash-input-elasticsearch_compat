# Releasing

1. **Version**: set the new version in `VERSION` and in `pom.xml` (`<version>`); they must match.
2. **Changelog**: add a `## <version>` section to `CHANGELOG.md`.
3. **Verify** (Docker required):
   ```bash
   mise install
   mvn verify -Pintegration            # unit + container tests, builds the gem
   jruby -S rspec spec/inputs          # Ruby specs
   ```
   Optionally `mvn verify -Pintegration,throughput` for the performance checks.
4. **Build** the gem and check it builds without warnings:
   ```bash
   mvn package -DskipTests
   jruby -S gem build logstash-input-elasticsearch_compat.gemspec
   ```
5. **Commit and tag**:
   ```bash
   git commit -am "release: v<version>"
   git tag -a v<version> -m "v<version>"
   git push origin main v<version>
   ```
6. **Publish** with the maintainer's RubyGems account (credentials are never stored in the repo):
   ```bash
   gem push logstash-input-elasticsearch_compat-<version>-java.gem
   ```
7. **Smoke test** the published gem: `bin/logstash-plugin install logstash-input-elasticsearch_compat`.

# Releasing

Releases are published by GitHub Actions ([release.yml](.github/workflows/release.yml)) when a
`v<version>` tag is pushed. [ci.yml](.github/workflows/ci.yml) runs the same tests on every push to
`main` and on pull requests.

## One-time setup

1. Create a [RubyGems](https://rubygems.org) account with MFA enabled.
2. On rubygems.org, add a **trusted publisher** (for a gem not yet published, use
   *Pending trusted publishers* in your profile):
   - Gem name: `logstash-input-elasticsearch_compat`
   - Repository owner / name: `techzilla` / `logstash-input-elasticsearch_compat`
   - Workflow filename: `release.yml`
   - Environment: `rubygems`
3. In the GitHub repository settings, create the `rubygems` environment. Optionally require a
   reviewer so each publish waits for approval.

No API key is stored anywhere: the workflow exchanges a short-lived GitHub OIDC token for a
RubyGems credential.

## Each release

1. **Version**: set the same version in `VERSION` and `pom.xml` (`<version>`).
2. **Changelog**: add a `## <version>` section to `CHANGELOG.md` (it becomes the GitHub release
   notes).
3. **Verify locally** (optional; CI repeats it), Docker required:
   ```bash
   mise install
   mvn verify -Pintegration
   jruby -S rspec spec/inputs
   ```
   Performance checks are not run in CI: `mvn verify -Pintegration,throughput`.
4. **Commit, tag, push**:
   ```bash
   git commit -am "release: v<version>"
   git tag -a v<version> -m "v<version>"
   git push origin main v<version>
   ```
   The release workflow checks that the tag, `VERSION`, `pom.xml` and the changelog agree, runs
   the full test suite, publishes the gem to RubyGems and creates a GitHub release with the gem
   attached.
5. **Smoke test** the published gem on a clean Logstash:
   `bin/logstash-plugin install logstash-input-elasticsearch_compat`.

## Manual fallback

```bash
mvn package -DskipTests
jruby -S gem build logstash-input-elasticsearch_compat.gemspec
gem push logstash-input-elasticsearch_compat-<version>-java.gem
```

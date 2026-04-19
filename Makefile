.PHONY: help format verify test it bench coverage sbom clean release-dryrun versions versions-update

MVN ?= ./mvnw

# Notes:
# - format / verify / test / it run via the Maven wrapper so contributors don't
#   need a system mvn. CI does the same. Spotless auto-applies on every compile
#   (Palantir Java Format), so explicit `make format` is rarely needed.
# - bench compiles tests then forks a fresh JVM with JMH on the test classpath.
# - release simulates a publish locally to target/staging-deploy; actual
#   Maven Central deploy happens via the GitHub release workflow on `v*` tags.

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

format: ## Auto-format all Java files (Google Java Format AOSP style)
	$(MVN) -B -ntp spotless:apply

verify: ## Format-check + tests + integration tests + coverage gate + SBOM
	$(MVN) -B -ntp verify

test: ## Unit tests only
	$(MVN) -B -ntp test

it: ## Integration tests only (Testcontainers + WireMock)
	$(MVN) -B -ntp verify -DskipUTs=true -Dsurefire.failIfNoSpecifiedTests=false

bench: ## Run JMH benchmarks (cardinality firewall, span events)
	$(MVN) -B -ntp -Pbench -DskipTests test-compile exec:exec

coverage: ## Open the JaCoCo HTML report
	$(MVN) -B -ntp verify
	@echo "Open target/site/jacoco/index.html"

sbom: ## Generate the CycloneDX SBOM
	$(MVN) -B -ntp -DskipTests package
	@echo "SBOM at target/pulse-*-sbom.{json,xml}"

clean: ## Clean build outputs
	$(MVN) -B -ntp clean

verify-all: format verify ## Format + everything (recommended pre-commit)

versions: ## Show available stable updates for properties, deps, and plugins (skips -M/-RC/-beta/-SNAPSHOT)
	@$(MVN) -B -ntp versions:display-property-updates versions:display-dependency-updates versions:display-plugin-updates -DallowMajorUpdates=false

versions-update: ## Bump <properties> and <dependency> versions to latest stable in-place; review the diff before committing
	$(MVN) -B -ntp versions:update-properties versions:use-latest-releases -DallowMajorUpdates=false
	@echo "Done. Review with 'git diff pom.xml' and run 'make verify' before committing."

release-dryrun: ## Stage release artifacts locally (signed sources/javadoc/jar) without publishing
	$(MVN) -B -ntp -Prelease deploy
	@echo "Staged in target/staging-deploy. Would publish via 'mvn -Prelease jreleaser:deploy' with JRELEASER_* env vars set."

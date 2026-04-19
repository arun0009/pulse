.PHONY: build up down logs demo all

REPO_ROOT := $(shell cd ../.. && pwd)

# Builds the local pulse SNAPSHOT, then both demo apps. Required once before `up`.
build:
	cd $(REPO_ROOT) && ./mvnw -B -ntp install -DskipTests -Dspotless.skip=true -Djacoco.skip=true
	$(REPO_ROOT)/mvnw -B -ntp package -DskipTests
	docker compose build

# Bring the stack up and wait for all six services to pass health.
up:
	docker compose up -d
	@echo "Waiting for services to become healthy..."
	@for i in $$(seq 1 60); do \
	  curl -fs http://localhost:8080/actuator/health >/dev/null 2>&1 && \
	  curl -fs http://localhost:8081/actuator/health >/dev/null 2>&1 && \
	  curl -fs http://localhost:8090/actuator/health >/dev/null 2>&1 && \
	  curl -fs http://localhost:9090/-/ready    >/dev/null 2>&1 && \
	  curl -fs http://localhost:3000/api/health >/dev/null 2>&1 && \
	  echo "All services UP after $$i second(s)" && exit 0 ; \
	  sleep 1 ; \
	done ; \
	echo "Timed out waiting for services. Check 'docker compose logs'." && exit 1
	@echo
	@echo "  Pulse Service Overview:  http://localhost:3000/d/pulse-overview  (admin / admin)"
	@echo "  Prometheus alerts:       http://localhost:9090/alerts"
	@echo "  Pulse self-diagnostics:  http://localhost:8080/actuator/pulse"

down:
	docker compose down -v

logs:
	docker compose logs --tail=120 edge-with-pulse edge-without-pulse downstream

demo:
	./scripts/demo.sh

# Build → up → demo → logs in one shot. The 90-second README promise.
all: build up demo logs

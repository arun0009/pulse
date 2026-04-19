#!/usr/bin/env bash
# Pulse showcase runner — exercises three production failure modes against
# both the with-Pulse (8080) and without-Pulse (8081) edge services so you can
# see the difference in plain text, side by side.
#
# Usage:
#   docker compose up -d --build
#   ./scripts/demo.sh
#   docker compose logs --tail=120 edge-with-pulse edge-without-pulse downstream

set -euo pipefail

WITH_PULSE="${WITH_PULSE:-http://localhost:8080}"
WITHOUT_PULSE="${WITHOUT_PULSE:-http://localhost:8081}"

bold()    { printf "\n\033[1m%s\033[0m\n" "$1"; }
warn()    { printf "\033[33m%s\033[0m\n" "$1"; }
ok()      { printf "\033[32m%s\033[0m\n" "$1"; }
sep()     { printf -- "----------------------------------------------------------------\n"; }

require_up() {
  curl -sf "$1/actuator/health" >/dev/null || {
    warn "Service at $1 is not responding. Did you run 'docker compose up -d --build'?"
    exit 1
  }
}

require_up "$WITH_PULSE"
require_up "$WITHOUT_PULSE"

# ─────────────────────────────────────────────────────────────────────────────
bold "[1/3] Custom MDC propagation across @Async"
echo "Send a request with Pulse-Tenant-Id, X-Request-ID, X-User-ID. The edge logs"
echo "those values both at request entry and inside an @Async worker thread."
echo
echo "Pulse: extracts the headers into MDC and propagates them across @Async."
echo "Stock Spring Boot 4: traceId propagates natively, but custom MDC keys do not."
sep
HDRS=(-H "Pulse-Tenant-Id: acme-corp" -H "X-Request-ID: req-001" -H "X-User-ID: alice")
curl -sf "${HDRS[@]}" "$WITH_PULSE/trace/async" >/dev/null
curl -sf "${HDRS[@]}" "$WITHOUT_PULSE/trace/async" >/dev/null
sleep 1
echo "with-pulse log lines (look for tenantId=acme-corp on BOTH lines):"
docker compose logs --no-log-prefix edge-with-pulse 2>/dev/null | grep "tenantId=" | tail -2 || \
  echo "  (run: docker compose logs --tail=20 edge-with-pulse | grep tenantId)"
echo
echo "without-pulse log lines (expect tenantId=null on BOTH lines):"
docker compose logs --no-log-prefix edge-without-pulse 2>/dev/null | grep "tenantId=" | tail -2 || \
  echo "  (run: docker compose logs --tail=20 edge-without-pulse | grep tenantId)"

# ─────────────────────────────────────────────────────────────────────────────
bold "[2/3] Cardinality firewall"
echo "Emit 13 distinct userIds tagged onto a counter named orders.placed."
echo "The demo configures Pulse to cap at 10 distinct values per meter."
sep
for i in $(seq 1 13); do
  curl -sf "$WITH_PULSE/trace/cardinality?id=user-$i" >/dev/null
  curl -sf "$WITHOUT_PULSE/trace/cardinality?id=user-$i" >/dev/null
done
WITH_LAST=$(curl -sf "$WITH_PULSE/trace/cardinality?id=user-99" || echo "{}")
WITHOUT_LAST=$(curl -sf "$WITHOUT_PULSE/trace/cardinality?id=user-99" || echo "{}")
echo "with-pulse    final distinct series: $WITH_LAST"
echo "without-pulse final distinct series: $WITHOUT_LAST"
echo
echo "Pulse caps and buckets the rest into a synthetic 'OVERFLOW' tag."
echo "Without Pulse, every userId becomes a permanent time series → cost bomb."

# ─────────────────────────────────────────────────────────────────────────────
bold "[3/3] Timeout-budget cascade"
echo "Edge calls a downstream that simulates 5s of work. The caller sets a 500ms"
echo "deadline via Pulse-Timeout-Ms. Pulse propagates it. Without Pulse, the header"
echo "is dropped and downstream sleeps the full 5 seconds."
sep
echo "with-pulse (expect ~500ms):"
START=$(perl -MTime::HiRes=time -e 'printf "%.0f\n", time*1000')
curl -sf --max-time 8 -H "Pulse-Timeout-Ms: 500" "$WITH_PULSE/trace/timeout" || true
END=$(perl -MTime::HiRes=time -e 'printf "%.0f\n", time*1000')
echo " (caller-perceived: $((END-START))ms)"
echo
echo "without-pulse (expect ~5000ms):"
START=$(perl -MTime::HiRes=time -e 'printf "%.0f\n", time*1000')
curl -sf --max-time 10 -H "Pulse-Timeout-Ms: 500" "$WITHOUT_PULSE/trace/timeout" || true
END=$(perl -MTime::HiRes=time -e 'printf "%.0f\n", time*1000')
echo " (caller-perceived: $((END-START))ms)"
sep

bold "Inspect the full picture:"
echo "  docker compose logs --tail=40 downstream         # see 'no Pulse-Timeout-Ms' vs 'honored'"
echo "  curl -s $WITH_PULSE/actuator/pulse | jq          # Pulse self-diagnostics"
echo "  curl -s $WITH_PULSE/actuator/metrics/orders.placed | jq '.availableTags'  # OVERFLOW tag"
ok "Done."

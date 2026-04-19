package io.github.arun0009.pulse.demo.downstream;

import io.github.arun0009.pulse.guardrails.TimeoutBudget;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately-slow downstream. If Pulse propagated the caller's deadline as {@code Pulse-Timeout-Ms},
 * we honor it and short-circuit. Otherwise, we sleep the full simulated 5 seconds — exactly the
 * cascade Pulse exists to prevent.
 */
@RestController
public class SlowController {

    private static final Logger log = LoggerFactory.getLogger(SlowController.class);
    private static final Duration WORK_DURATION = Duration.ofMillis(5000);

    @GetMapping("/slow")
    public ResponseEntity<String> slow(HttpServletRequest request) throws InterruptedException {
        Optional<TimeoutBudget> budget = TimeoutBudget.current();

        if (budget.isPresent()) {
            Duration remaining = budget.get().remaining();
            log.info(
                    "[downstream] received call from caller with Pulse-Timeout-Ms — remaining budget: {}ms",
                    remaining.toMillis());

            if (remaining.compareTo(WORK_DURATION) < 0) {
                long sleepMs = remaining.toMillis();
                Thread.sleep(Math.max(0, sleepMs));
                log.warn("[downstream] honored caller's deadline; gave up after {}ms — would have taken {}ms",
                        sleepMs, WORK_DURATION.toMillis());
                return ResponseEntity.status(504)
                        .body("deadline-honored after " + sleepMs + "ms (caller's budget exhausted)");
            }
        } else {
            log.info("[downstream] no Pulse-Timeout-Ms from caller — falling back to full {}ms work",
                    WORK_DURATION.toMillis());
        }

        Thread.sleep(WORK_DURATION.toMillis());
        return ResponseEntity.ok("slow-result after " + WORK_DURATION.toMillis() + "ms");
    }
}

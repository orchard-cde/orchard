package dev.orchard.e2e;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that skips LocalStack-backed tests when Docker is not available.
 * Mirrors {@link QemuPrerequisiteExtension}'s pattern.
 */
public class LocalStackPrerequisiteExtension implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            // Probe Docker by asking Testcontainers if it can find a usable Docker host.
            org.testcontainers.DockerClientFactory.instance().client();
            return ConditionEvaluationResult.enabled("Docker reachable; LocalStack tests enabled");
        } catch (Throwable t) {
            return ConditionEvaluationResult.disabled(
                "Docker not reachable — skipping LocalStack tests (" + t.getMessage() + ")");
        }
    }
}

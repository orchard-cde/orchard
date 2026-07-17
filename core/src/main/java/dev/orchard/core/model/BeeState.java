package dev.orchard.core.model;

public enum BeeState {
    HATCHING,
    HIBERNATING,
    BUZZING,
    POLLINATING,
    SMOKED;

    public boolean canTransitionTo(BeeState target) {
        if (target == SMOKED) return true;
        return switch (this) {
            case HATCHING -> target == HIBERNATING;
            case HIBERNATING -> target == BUZZING;
            case BUZZING -> target == POLLINATING || target == HIBERNATING;
            case POLLINATING -> target == BUZZING;
            case SMOKED -> false;
        };
    }
}

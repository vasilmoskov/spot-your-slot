package bg.spotyourslot.business.domain;

public enum BusinessStatus {
    DRAFT,
    ACTIVE,
    SUSPENDED;

    public boolean canTransitionTo(BusinessStatus target) {
        return switch (this) {
            case DRAFT -> target == ACTIVE;
            case ACTIVE -> target == SUSPENDED;
            case SUSPENDED -> target == ACTIVE;
        };
    }
}

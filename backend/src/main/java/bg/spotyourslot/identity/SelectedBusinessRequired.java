package bg.spotyourslot.identity;

public final class SelectedBusinessRequired extends RuntimeException {
    public SelectedBusinessRequired() {
        super("Selected Business context is required");
    }
}

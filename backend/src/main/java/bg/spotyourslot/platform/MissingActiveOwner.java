package bg.spotyourslot.platform;

public final class MissingActiveOwner extends RuntimeException {
    public MissingActiveOwner() {
        super("Business requires an active owner");
    }
}

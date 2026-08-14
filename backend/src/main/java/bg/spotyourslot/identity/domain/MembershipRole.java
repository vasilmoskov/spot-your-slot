package bg.spotyourslot.identity.domain;

public enum MembershipRole {
    BUSINESS_OWNER,
    MANAGER,
    STAFF;

    public boolean canManageMemberships() {
        return this == BUSINESS_OWNER;
    }

    public boolean canManageOperations() {
        return this == BUSINESS_OWNER || this == MANAGER;
    }
}

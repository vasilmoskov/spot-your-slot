package bg.spotyourslot.architecture;

import bg.spotyourslot.SpotYourSlotApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTests {
    @Test
    void modulesAreAcyclic() {
        ApplicationModules.of(SpotYourSlotApplication.class).verify();
    }
}

package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.application.IdentityRecords.DeliveredLink;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

public interface DevelopmentMailbox {
    void deliver(String kind, String recipient, String url);

    List<DeliveredLink> messages();

    @Component
    @Profile({"dev", "test"})
    final class InMemory implements DevelopmentMailbox {
        private static final int LIMIT = 50;

        private final ArrayDeque<DeliveredLink> links = new ArrayDeque<>();
        private final Clock clock;

        public InMemory(Clock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized void deliver(String kind, String recipient, String url) {
            if (links.size() == LIMIT) {
                links.removeFirst();
            }
            links.addLast(new DeliveredLink(UUID.randomUUID(), kind, recipient, url, clock.instant()));
        }

        @Override
        public synchronized List<DeliveredLink> messages() {
            return List.copyOf(links);
        }
    }

    @Component
    @Profile("prod")
    final class Disabled implements DevelopmentMailbox {
        @Override
        public void deliver(String kind, String recipient, String url) {
            // Real delivery belongs to a later phase.
        }

        @Override
        public List<DeliveredLink> messages() {
            return List.of();
        }
    }
}

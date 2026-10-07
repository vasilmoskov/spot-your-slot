package bg.spotyourslot.publicbooking.web;

import bg.spotyourslot.booking.PublicBookingRateLimiter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the limiter on exactly the three public booking routes and nothing else. */
@Configuration(proxyBeanMethods = false)
class PublicBookingWebConfiguration implements WebMvcConfigurer {
    private static final String PREFIX = "/api/public/businesses/{slug}";

    private final PublicBookingRateLimiter limiter;

    PublicBookingWebConfiguration(PublicBookingRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(PublicBookingRateLimitInterceptor.forReads(limiter))
                .addPathPatterns(
                        PREFIX + "/services/{serviceId}/booking-options",
                        PREFIX + "/services/{serviceId}/availability");
        registry.addInterceptor(PublicBookingRateLimitInterceptor.forBooking(limiter))
                .addPathPatterns(PREFIX + "/bookings");
    }
}

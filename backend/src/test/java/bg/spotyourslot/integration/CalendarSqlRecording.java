package bg.spotyourslot.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test instrumentation for the calendar read tests. It wraps the application's {@link DataSource}
 * and records, per calling thread, the SQL text handed to {@code Connection.prepareStatement}, so a
 * test counts the statements of one service call exactly. It can also run a one-shot hook
 * immediately before a chosen statement is prepared, on the calling thread and inside its
 * transaction: a test uses it to commit a change from another transaction at a deterministic point
 * between two statements of the same read, ordered by the call itself (no sleeps). It also supplies
 * a controllable clock.
 */
@TestConfiguration
public class CalendarSqlRecording {
    public static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");

    private static final List<Entry> RECORDED = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicReference<Hook> HOOK = new AtomicReference<>();

    private record Entry(long threadId, String sql) {
    }

    private record Hook(long threadId, Predicate<String> matches, Runnable action) {
    }

    public static void clear() {
        RECORDED.clear();
        HOOK.set(null);
    }

    /** The SQL statements the current thread has prepared since the last {@link #clear()}. */
    public static List<String> statementsOfCurrentThread() {
        long id = Thread.currentThread().threadId();
        synchronized (RECORDED) {
            return RECORDED.stream().filter(entry -> entry.threadId() == id).map(Entry::sql).toList();
        }
    }

    /** Runs {@code action} once, on the current thread, just before the next matching statement. */
    public static void beforeNextStatement(Predicate<String> matches, Runnable action) {
        HOOK.set(new Hook(Thread.currentThread().threadId(), matches, action));
    }

    public static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase();
    }

    @Bean
    @Primary
    MutableTestClock calendarTestClock() {
        return new MutableTestClock(NOW);
    }

    @Bean
    static BeanPostProcessor calendarStatementRecordingDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource ? recordingDataSource(dataSource) : bean;
            }
        };
    }

    private static DataSource recordingDataSource(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (method.getName().equals("getConnection") && result instanceof Connection connection) {
                        return recordingConnection(connection);
                    }
                    return result;
                });
    }

    private static Connection recordingConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ((method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql) {
                        long thread = Thread.currentThread().threadId();
                        Hook hook = HOOK.get();
                        if (hook != null && hook.threadId() == thread && hook.matches().test(sql)
                                && HOOK.compareAndSet(hook, null)) {
                            hook.action().run();
                        }
                        RECORDED.add(new Entry(thread, sql));
                    }
                    return invoke(target, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}

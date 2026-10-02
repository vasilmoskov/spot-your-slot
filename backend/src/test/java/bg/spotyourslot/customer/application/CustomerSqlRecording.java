package bg.spotyourslot.customer.application;

import bg.spotyourslot.integration.MutableTestClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test instrumentation that counts Customer-capability SQL. It wraps the application's
 * {@link DataSource} and records, with the id of the calling thread, only the SQL text handed to
 * {@code Connection.prepareStatement} or {@code prepareCall} that names the {@code customer}
 * table as a whole word. Statements on other tables (including the test probe table, whose columns
 * merely start with {@code customer_}), transaction setup, and other driver calls are not counted.
 * Each Customer store operation is exactly one statement, so the count is the number of Customer
 * SQL statements a call ran. It also supplies a controllable, read-counting {@code Clock}.
 */
@TestConfiguration
public class CustomerSqlRecording {
    public static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");

    private static final Pattern CUSTOMER_TABLE = Pattern.compile("\\bcustomer\\b", Pattern.CASE_INSENSITIVE);
    private static final List<Entry> RECORDED = Collections.synchronizedList(new ArrayList<>());

    private record Entry(long threadId, String sql) {
    }

    /** Forgets everything recorded so far. */
    public static void clear() {
        RECORDED.clear();
    }

    /** The Customer SQL statements the given thread has run since the last {@link #clear()}. */
    public static List<String> statementsOf(long threadId) {
        synchronized (RECORDED) {
            return RECORDED.stream().filter(entry -> entry.threadId() == threadId).map(Entry::sql).toList();
        }
    }

    public static List<String> statementsOfCurrentThread() {
        return statementsOf(Thread.currentThread().threadId());
    }

    @Bean
    @Primary
    MutableTestClock customerTestClock() {
        return new MutableTestClock(NOW);
    }

    @Bean
    static BeanPostProcessor customerStatementRecordingDataSource() {
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
                            && args[0] instanceof String sql
                            && CUSTOMER_TABLE.matcher(sql).find()) {
                        RECORDED.add(new Entry(Thread.currentThread().threadId(), sql));
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

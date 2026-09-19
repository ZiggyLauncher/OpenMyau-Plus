package myau.event;

import myau.event.types.Priority;

import java.lang.annotation.*;

/**
 * Marks a method so that the EventManager knows that it should be registered.
 * The priority of the method is also set with this.
 *
 * @author DarkMagician6
 * @see Priority
 * @since July 30, 2013
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventTarget {
    byte value() default Priority.MEDIUM;

    /**
     * Handlers declared on a {@link myau.module.Module} are skipped by the EventManager while
     * the module is disabled. Set this on the few handlers that must keep running regardless -
     * world-load resets, delay bookkeeping, flushing queued packets, releasing injected input.
     */
    boolean runWhenDisabled() default false;
}

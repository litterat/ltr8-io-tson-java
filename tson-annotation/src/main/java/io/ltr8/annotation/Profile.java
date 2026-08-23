package io.ltr8.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which binding profiles a constructor serves, so one class can be bound differently by different contexts.
 *
 * <p><b>The case this exists for</b> is a server speaking several versions of one schema at once. Each
 * version gets its own {@code DataBindContext}, named by {@code DataBindContext.Builder#profile}, and a class
 * offers a constructor per version:
 *
 * <pre>{@code
 * public record Order(String sku, int quantity, String currency) {
 *     @Profile(value = "api-3", fields = {"sku", "quantity"})
 *     public Order(String sku, int quantity) { this(sku, quantity, "AUD"); }
 * }
 * }</pre>
 *
 * <p><b>The profile name means nothing here.</b> It is a label the caller chose, matched by equality against
 * the context's own; nothing in this module knows what schema it stands for, or that schemas exist. That is
 * deliberate -- it keeps the selection where the class and the context are, without teaching a generic
 * binding engine about a format. Prefer a stable label ({@code "api-3"}) to a schema identity: an identity
 * changes with the version, which is exactly when you want the annotation to stay put.
 *
 * <p><b>{@code fields} is needed only when reflection cannot supply the names.</b> A record's <em>canonical</em>
 * constructor keeps its parameter names in the class file, so a profile on that one needs no list; a
 * secondary constructor's are {@code arg0}, {@code arg1} unless the class was compiled with
 * {@code -parameters}, and this is how to give them without that flag. Each name must match a component the
 * class declares, and the order is the constructor's own parameter order. {@link Field} on the parameters
 * does the same job one at a time.
 *
 * <p><b>It designates as well as selects</b>, so a constructor carrying it needs no {@link Record} beside it.
 * The two answer different questions: {@code @Record} names the creator, this names the creator <em>for a
 * given profile</em>, and a class may have both -- the {@code @Record} one then serves every profile that
 * does not name a constructor of its own.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.CONSTRUCTOR)
public @interface Profile {

    /** The profile names this constructor serves; a class may serve several from one constructor. */
    String[] value();

    /** This constructor's parameter names, in order -- only where reflection cannot supply them. */
    String[] fields() default {};
}

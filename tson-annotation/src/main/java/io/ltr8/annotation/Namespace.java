package io.ltr8.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allow a class or package to have the external schema name to be specified.
 * It is up to the external schema solution to decide how to interpret this value.
 * This annotation is not being used by litterat-bind and is more related to the
 * litterat-model.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.PACKAGE})
public @interface Namespace {
    // specify the model type.
    String value();
}

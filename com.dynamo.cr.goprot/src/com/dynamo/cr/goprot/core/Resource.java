package com.dynamo.cr.goprot.core;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import com.dynamo.cr.properties.Validator;

@Validator(validator=ResourceValidator.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface Resource {
}

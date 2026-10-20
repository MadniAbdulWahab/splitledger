package dev.splitledger.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Bean Validation constraint for optional IBAN fields; null means "no IBAN given" and is valid. */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidIban.Validator.class)
public @interface ValidIban {

    String message() default "is not a valid IBAN";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidIban, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || Iban.isValid(value);
        }
    }
}

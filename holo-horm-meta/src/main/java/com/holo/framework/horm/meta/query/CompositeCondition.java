package com.holo.framework.horm.meta.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-private renderer for AND/OR/NOT composite conditions. Each composite
 * wraps its children in parentheses so nested combinations preserve intent
 * regardless of operator precedence.
 */
final class CompositeCondition implements Condition {

    private final String fragment;
    private final List<Object> bindings;

    private CompositeCondition(String fragment, List<Object> bindings) {
        this.fragment = fragment;
        this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
    }

    static Condition and(Condition... conditions) {
        if (conditions == null || conditions.length == 0) {
            throw new IllegalArgumentException("AND requires at least one condition");
        }
        if (conditions.length == 1) {
            return conditions[0];
        }
        return join(" AND ", conditions);
    }

    static Condition or(Condition... conditions) {
        if (conditions == null || conditions.length == 0) {
            throw new IllegalArgumentException("OR requires at least one condition");
        }
        if (conditions.length == 1) {
            return conditions[0];
        }
        return join(" OR ", conditions);
    }

    static Condition not(Condition condition) {
        if (condition == null) {
            throw new IllegalArgumentException("NOT requires a condition");
        }
        return new CompositeCondition(
            "NOT (" + condition.sqlFragment() + ")",
            condition.bindings()
        );
    }

    private static Condition join(String separator, Condition... conditions) {
        StringBuilder sb = new StringBuilder();
        List<Object> allBindings = new ArrayList<>();
        for (int i = 0; i < conditions.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append("(").append(conditions[i].sqlFragment()).append(")");
            allBindings.addAll(conditions[i].bindings());
        }
        return new CompositeCondition(sb.toString(), allBindings);
    }

    @Override
    public String sqlFragment() {
        return fragment;
    }

    @Override
    public List<Object> bindings() {
        return bindings;
    }
}

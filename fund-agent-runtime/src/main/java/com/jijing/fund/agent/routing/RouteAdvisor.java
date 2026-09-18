package com.jijing.fund.agent.routing;

import java.util.Optional;

/** Supplies semantic routing advice; callers must retain deterministic safety authority. */
@FunctionalInterface
public interface RouteAdvisor {
    Optional<RouteAdvice> advise(String message,RouteFeatures deterministicFeatures);
}

package com.jijing.fund.agent.routing;

import java.util.List;

/** A non-authoritative semantic assessment used only after mandatory routing rules. */
public record RouteAdvice(List<String> goals,List<String> requiredCapabilities,
                          boolean hasDependencies,boolean crossSourceVerificationRequired,
                          boolean iterativeResearchRequired,int estimatedStages,String rationale) {
    public RouteAdvice {
        goals=normalize(goals,8,120);
        requiredCapabilities=normalize(requiredCapabilities,12,64);
        estimatedStages=Math.max(1,Math.min(20,estimatedStages));
        rationale=rationale==null?"":rationale.substring(0,Math.min(500,rationale.length()));
    }

    private static List<String> normalize(List<String> values,int maxItems,int maxChars){
        if(values==null)return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(v->!v.isEmpty())
                .map(v->v.substring(0,Math.min(maxChars,v.length()))).distinct().limit(maxItems).toList();
    }
}

package com.jijing.fund.testsupport.rag;
import java.util.*;
public final class CitationMetricCalculator {
    public CitationMetrics calculate(Set<String>required,Set<String>allowed,Collection<String>actual){long valid=actual.stream().filter(allowed::contains).count();double precision=actual.isEmpty()?1:(double)valid/actual.size();long covered=required.stream().filter(actual::contains).count();double coverage=required.isEmpty()?1:(double)covered/required.size();return new CitationMetrics(precision,coverage);}
    public record CitationMetrics(double precision,double coverage){}
}

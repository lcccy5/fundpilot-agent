package com.jijing.fund.agent.verification;

/** Verifier may return a plan at most once. */
public final class VerifierReturnPolicy {
    public static final int MAX_RETURNS=1;
    
    /** 判断 allowReturn 对应的条件是否成立。 */
    public boolean allowReturn(int alreadyReturned){return alreadyReturned<MAX_RETURNS;}
}

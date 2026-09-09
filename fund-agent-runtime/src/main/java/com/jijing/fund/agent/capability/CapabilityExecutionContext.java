package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask;
import java.util.function.Consumer;

/** Carries execution identity and cooperative cancellation across capability and graph boundaries. */
public record CapabilityExecutionContext(ClaimedTask task,Runnable ownershipCheck,Consumer<Runnable> persistence) {
    /** Requires all guards; production workers supply a transactional repository guard. */
    public CapabilityExecutionContext {
        if(task==null||ownershipCheck==null||persistence==null)throw new IllegalArgumentException("task and lease guards are required");
    }
    /** Standalone capability evaluation has no durable write authorization. */
    public CapabilityExecutionContext(ClaimedTask task){this(task,()->{},writes->{throw new IllegalStateException("durable writes require a worker lease");});}
    /** Check before every new external operation; in-flight external requests may not be cancellable. */
    public void checkActive(){ownershipCheck.run();}
    /** Commits short checkpoint/event writes atomically with the lease check; never wrap external I/O. */
    public void persist(Runnable writes){persistence.accept(writes);}
}

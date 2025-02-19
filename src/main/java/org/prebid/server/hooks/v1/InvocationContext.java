package org.prebid.server.hooks.v1;

import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.model.Endpoint;

public interface InvocationContext {

    Timeout timeout();

    Endpoint endpoint();
}

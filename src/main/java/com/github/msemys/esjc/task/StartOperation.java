package com.github.msemys.esjc.task;

import com.github.msemys.esjc.operation.Operation;

import static com.github.msemys.esjc.util.Preconditions.checkNotNull;

public class StartOperation implements Task {
    public final Operation operation;

    public StartOperation(Operation operation) {
        checkNotNull(operation, "operation");
        this.operation = operation;
    }
}

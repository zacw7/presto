/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.function;

public enum FunctionImplementationType
{
    JAVA(false, true),
    SQL(false, true),
    THRIFT(true, false),
    GRPC(true, false),
    CPP(false, false),
    REST(true, false);

    private final boolean externalExecution;
    private final boolean evaluatedInCoordinator;

    FunctionImplementationType(boolean externalExecution, boolean evaluatedInCoordinator)
    {
        this.externalExecution = externalExecution;
        this.evaluatedInCoordinator = evaluatedInCoordinator;
    }

    public boolean isExternalExecution()
    {
        return externalExecution;
    }

    public boolean canBeEvaluatedInCoordinator()
    {
        return evaluatedInCoordinator;
    }
}

/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.process.management;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import org.kie.kogito.process.ProcessInstanceExecutionException;

@Provider
public class ProcessInstanceExecutionExceptionMapper implements ExceptionMapper<ProcessInstanceExecutionException> {

    @Override
    public Response toResponse(ProcessInstanceExecutionException exception) {
        Map<String, String> data = new HashMap<>();
        data.put("id", exception.getProcessInstanceId());
        data.put("failedNodeId", exception.getFailedNodeId());
        data.put("message", exception.getErrorMessage());
        
        return Response.serverError().header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON).entity(data).build();
    }

}

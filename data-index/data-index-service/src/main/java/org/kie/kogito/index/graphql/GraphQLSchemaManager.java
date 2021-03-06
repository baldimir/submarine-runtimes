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

package org.kie.kogito.index.graphql;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.quarkus.arc.Arc;
import io.vertx.axle.core.eventbus.EventBus;
import io.vertx.axle.core.eventbus.Message;
import io.vertx.axle.core.eventbus.MessageConsumer;
import io.vertx.axle.core.eventbus.MessageProducer;
import org.kie.kogito.index.cache.CacheService;
import org.kie.kogito.index.model.ProcessInstanceState;
import org.kie.kogito.index.query.QueryService;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.kogito.index.json.JsonUtils.getObjectMapper;

@ApplicationScoped
public class GraphQLSchemaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLSchemaManager.class);
    private static final String PROCESS_INSTANCE_ADDED = "ProcessInstanceAdded";
    private static final String PROCESS_INSTANCE_UPDATED = "ProcessInstanceUpdated";
    private static final String USER_TASK_INSTANCE_ADDED = "UserTaskInstanceAdded";
    private static final String USER_TASK_INSTANCE_UPDATED = "UserTaskInstanceUpdated";

    @Inject
    QueryService queryService;

    @Inject
    CacheService cacheService;

    @Inject
    GraphQLScalarType qlDateTimeScalarType;

    private ConcurrentMap<String, MessageProducer> producers = new ConcurrentHashMap<>();
    private GraphQLSchema schema;

    @PostConstruct
    public void setup() {
        schema = createSchema();
    }

    @PreDestroy
    public void destroy() {
        producers.values().forEach(MessageProducer::close);
    }

    private GraphQLSchema createSchema() {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("schema.graphqls");

        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(new InputStreamReader(stream));

        RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", builder -> {
                    builder.dataFetcher("ProcessInstances", this::getProcessInstancesValues);
                    builder.dataFetcher("UserTaskInstances", this::getUserTaskInstancesValues);
                    return builder;
                })
                .type("ProcessInstanceState", builder -> {
                    builder.enumValues(name -> ProcessInstanceState.valueOf(name).ordinal());
                    return builder;
                })
                .type("Subscription", builder -> {
                    builder.dataFetcher(PROCESS_INSTANCE_ADDED, getProcessInstanceAddedDataFetcher());
                    builder.dataFetcher(PROCESS_INSTANCE_UPDATED, getProcessInstanceUpdatedDataFetcher());
                    builder.dataFetcher(USER_TASK_INSTANCE_ADDED, getUserTaskInstanceAddedDataFetcher());
                    builder.dataFetcher(USER_TASK_INSTANCE_UPDATED, getUserTaskInstanceUpdatedDataFetcher());
                    return builder;
                })
                .scalar(qlDateTimeScalarType)
                .build();

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        return schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
    }

    private Collection<ObjectNode> getProcessInstancesValues(DataFetchingEnvironment env) {
        Map<String, Object> filter = env.getArgument("filter");
        return queryService.queryProcessInstances(new ProcessInstanceFilterMapper().apply(filter));
    }

    private Collection<ObjectNode> getUserTaskInstancesValues(DataFetchingEnvironment env) {
        Map<String, Object> filter = env.getArgument("filter");
        return queryService.queryUserTaskInstances(new UserTaskInstanceFilterMapper().apply(filter));
    }

    private DataFetcher<Publisher<ObjectNode>> getProcessInstanceAddedDataFetcher() {
        return env -> {
            return createPublisher(PROCESS_INSTANCE_ADDED, producer -> cacheService.getProcessInstancesCache().addObjectCreatedListener(pi -> producer.write(getObjectMapper().convertValue(pi, ObjectNode.class))));
        };
    }

    private DataFetcher<Publisher<ObjectNode>> getProcessInstanceUpdatedDataFetcher() {
        return env -> {
            return createPublisher(PROCESS_INSTANCE_UPDATED, producer -> cacheService.getProcessInstancesCache().addObjectUpdatedListener(pi -> producer.write(getObjectMapper().convertValue(pi, ObjectNode.class))));
        };
    }

    private DataFetcher<Publisher<ObjectNode>> getUserTaskInstanceAddedDataFetcher() {
        return env -> {
            return createPublisher(USER_TASK_INSTANCE_ADDED, producer -> cacheService.getUserTaskInstancesCache().addObjectCreatedListener(ut -> producer.write(getObjectMapper().convertValue(ut, ObjectNode.class))));
        };
    }

    private DataFetcher<Publisher<ObjectNode>> getUserTaskInstanceUpdatedDataFetcher() {
        return env -> {
            return createPublisher(USER_TASK_INSTANCE_UPDATED, producer -> cacheService.getUserTaskInstancesCache().addObjectUpdatedListener(ut -> producer.write(getObjectMapper().convertValue(ut, ObjectNode.class))));
        };
    }

    private Publisher<ObjectNode> createPublisher(String address, Consumer<MessageProducer<ObjectNode>> consumer) {
        EventBus eventBus = Arc.container().instance(EventBus.class).get();

        LOGGER.debug("Creating new message consumer for EventBus address: {}", address);
        MessageConsumer<ObjectNode> messageConsumer = eventBus.consumer(address);
        Publisher<ObjectNode> publisher = messageConsumer.toPublisherBuilder().map(Message::body).buildRs();

        producers.computeIfAbsent(address, key -> {
            LOGGER.debug("Creating new message publisher for EventBus address: {}", address);
            MessageProducer<ObjectNode> producer = eventBus.publisher(address);
            consumer.accept(producer);
            return producer;
        });

        return publisher;
    }

    protected DataFetcher<Publisher<ObjectNode>> getDomainModelUpdatedDataFetcher(String processId) {
        return env -> {
            return createPublisher(processId + "Updated", producer -> cacheService.getDomainModelCache(processId).addObjectUpdatedListener(domain -> producer.write(domain)));
        };
    }

    protected DataFetcher<Publisher<ObjectNode>> getDomainModelAddedDataFetcher(String processId) {
        return env -> {
            return createPublisher(processId + "Added", producer -> cacheService.getDomainModelCache(processId).addObjectCreatedListener(domain -> producer.write(domain)));
        };
    }

    protected DataFetcher<Collection<ObjectNode>> getDomainModelDataFetcher(String processId) {
        return env -> {
            String query = env.getArgument("query");
            return queryService.queryDomain(processId, query);
        };
    }

    public GraphQLSchema getGraphQLSchema() {
        return schema;
    }

    public void transform(Consumer<GraphQLSchema.Builder> builder) {
        schema = schema.transform(builder);
    }
}

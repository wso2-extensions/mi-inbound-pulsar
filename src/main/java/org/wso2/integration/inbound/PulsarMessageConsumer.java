/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.integration.inbound;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pulsar.client.api.BatchReceivePolicy;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.RegexSubscriptionMode;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericPollingConsumer;
import org.wso2.integration.inbound.connection.PulsarConnectionSetup;
import org.wso2.integration.inbound.pojo.ConnectionConfiguration;
import org.wso2.integration.inbound.utils.PulsarConstants;
import org.wso2.integration.inbound.utils.PulsarUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.SortedMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class PulsarMessageConsumer extends GenericPollingConsumer {

    private static final Log log = LogFactory.getLog(PulsarMessageConsumer.class);

    private PulsarClient client;
    private Consumer consumer;
    private final ConnectionConfiguration configuration;

    private String topicNames;
    private String topicsPattern;
    private String subscriptionName;
    private RegexSubscriptionMode subscriptionTopicsMode;
    private SubscriptionType subscriptionType;
    private SubscriptionInitialPosition subscriptionInitialPosition;
    private String consumerName;
    private Integer messageWaitTimeout = 1000;
    private Long ackTimeoutMillis;
    private Long nackRedeliveryDelayMillis;
    private Integer priorityLevel;
    private Integer receiverQueueSize;
    private Integer maxTotalReceiverQueueSizeAcrossPartitions;
    private String dlqTopic;
    private Integer dlqMaxRedeliverCount;
    private String contentType;
    private Boolean receiveSync = true;

    // Configuration for chunked messages
    private Boolean autoAckOldestChunkedMessageOnQueueFull;
    private Integer maxPendingChunkedMessage;
    private Long expiryTimeOfIncompleteChunkedMessageMillis;

    private Boolean autoUpdatePartitions;
    private Integer autoUpdatePartitionsIntervalSeconds;
    private Boolean replicateSubscriptionState;
    private Boolean readCompacted;

    // Batching related configurations
    private Boolean batchReceiveEnabled;
    private Integer batchingMaxMessages;
    private Integer batchingMaxBytes;
    private Integer batchingTimeout;
    private Boolean batchIndexAcknowledgmentEnabled;

    // Thread pool for processing messages asynchronously
    int corePoolSize = 4;       // minimum number of threads
    int maxPoolSize = 8;        // maximum threads
    int queueCapacity = 100;    // max number of tasks in the queue

    ExecutorService boundedExecutor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity), // bounded queue
            Executors.defaultThreadFactory(),
            // Policy: what happens when queue is full and max threads are busy
            new ThreadPoolExecutor.CallerRunsPolicy() // Runs task in calling thread
    );

    public PulsarMessageConsumer(Properties properties, String name, SynapseEnvironment synapseEnvironment,
                                 long scanInterval, String injectingSeq, String onErrorSeq, boolean coordination,
                                 boolean sequential) {

        super(properties, name, synapseEnvironment, scanInterval, injectingSeq, onErrorSeq, coordination, true);
        configuration = PulsarUtils.getConnectionConfigFromProperties(properties);
        getConsumerConfigFromProperties(properties);
    }

    @Override
    public Object poll() {

        try {
            if (consumer == null) {
                consumer = createConsumer(configuration);
            }
            if (!consumer.isConnected()) {
                if (log.isDebugEnabled()) {
                    log.debug("Connecting to Pulsar broker for consumer: " + consumer.getConsumerName());
                }
                client = createPulsarConnection(configuration);
                consumer = createConsumer(configuration);
            }
            consumeMessages();

        } catch (PulsarClientException e) {
            log.error("Error while consuming the message from Apache Pulsar." + e);
        }

        return null;
    }

    private void consumeMessages() throws PulsarClientException {
        if (batchReceiveEnabled) {
            if (receiveSync) {
                Messages<String> messages = consumer.batchReceive();
                if (messages.size() == 0) {
                    return;
                }
                if (batchIndexAcknowledgmentEnabled) {
                    // Process each message in the batch
                    for (Message<String> msg : messages) {
                        processMessage(msg);
                    }
                } else {
                    processBatch(messages);
                }
            } else {
                CompletableFuture<Messages<String>> future = consumer.batchReceiveAsync();

                future.thenAccept(messages -> {
                    if (messages.size() == 0) {
                        return;
                    }
                    if (batchIndexAcknowledgmentEnabled) {
                        processBatchAsync(messages);
                    } else {
                        for (Message<String> msg : messages) {
                            processMessageAsync(msg);
                        }
                    }
                });
            }
        } else {
            if (receiveSync) {
                // Synchronous single message receive
                Message<String> msg = consumer.receive(messageWaitTimeout, TimeUnit.MILLISECONDS);  // Synchronous call
                if (msg != null) {
                    processMessage(msg);
                }
            } else {
                // Asynchronous single message receive
                CompletableFuture<Message<String>> future = consumer.receiveAsync();
                future.thenAccept(msg -> {
                    if (msg != null) {
                        processMessageAsync(msg);
                    }
                });
            }
        }
    }

    private void processBatchAsync(Messages<String> messages) {
        boundedExecutor.submit(() -> {
            boolean isConsumed = true;
            for (Message<String> msg : messages) {
                MessageContext msgCtx = PulsarUtils.populateMessageContext(msg, synapseEnvironment);
                isConsumed = injectMessage(msg.getValue(), contentType, msgCtx);

                if (!isConsumed) {
                    break;
                }
            }

            if (isConsumed) {
                // Acknowledge all messages in the batch
                consumer.acknowledgeAsync(messages);
            } else {
                // Negative acknowledge all messages in the batch for retry
                consumer.negativeAcknowledge(messages);
                if (log.isDebugEnabled()) {
                    log.debug("Batch of messages was not consumed successfully. Negative acknowledgment sent for retry.");
                }
            }
        });
    }

    private void processMessageAsync(Message<String> msg) {

        try {
            boundedExecutor.submit(() -> {
                try {
                    MessageContext msgCtx = PulsarUtils.populateMessageContext(msg, synapseEnvironment);
                    boolean isConsumed = injectMessage(msg.getValue(), contentType, msgCtx);

                    if (isConsumed) {
                        // Acknowledge the message
                        consumer.acknowledgeAsync(msg);
                    } else {
                        // Negative ack for retry
                        consumer.negativeAcknowledge(msg);
                        if (log.isDebugEnabled()) {
                            log.debug("Message with ID: " + msg.getMessageId() + " was not consumed successfully. "
                                    + "Negative acknowledgment sent for retry.");
                        }
                    }
                } catch (Exception e) {
                    log.error("Error processing message asynchronously. Hence, rejecting the message with ID: " + msg.getMessageId(), e);
                    consumer.negativeAcknowledge(msg);
                }
            });
        } catch (RejectedExecutionException ex) {
            log.error("Cannot process new messages because all worker threads are busy. "
                    + "Hence, rejecting the message.", ex);
            consumer.negativeAcknowledge(msg);
        }
    }

    private void processBatch(Messages<String> messages) throws PulsarClientException {
        boolean isConsumed = true;
        for (Message<String> msg : messages) {
            MessageContext msgCtx = PulsarUtils.populateMessageContext(msg, synapseEnvironment);
            isConsumed = injectMessage(msg.getValue(), contentType, msgCtx);
            if (!isConsumed) {
                break;
            }
        }
        if (isConsumed) {
            // Acknowledge all messages in the batch
            consumer.acknowledge(messages);
        } else {
            // Negative acknowledge all messages in the batch for retry
            consumer.negativeAcknowledge(messages);
            if (log.isDebugEnabled()) {
                log.debug("Batch of messages was not consumed successfully. Negative acknowledgment sent for retry.");
            }
        }
    }

    private void processMessage(Message<String> msg) throws PulsarClientException {
        MessageContext msgCtx = PulsarUtils.populateMessageContext(msg, synapseEnvironment);
        boolean isConsumed = injectMessage(msg.getValue(), contentType, msgCtx);

        if (isConsumed) {
            // Acknowledge the message
            consumer.acknowledge(msg);
        } else {
            // Negative ack for retry
            consumer.negativeAcknowledge(msg);
        }
    }

    private PulsarClient createPulsarConnection(ConnectionConfiguration configuration) throws PulsarClientException {
        if (client == null) {
            ClientBuilder clientBuilder = PulsarClient.builder();
            PulsarConnectionSetup connectionSetup = new PulsarConnectionSetup();
            connectionSetup.constructClientBuilder(configuration, clientBuilder);
            this.client = clientBuilder.build();
        }
        return client;
    }

    private Consumer createConsumer(ConnectionConfiguration configuration) throws PulsarClientException {
        // Logic to create and return a Pulsar consumer based on the configuration
        // This will involve using the Pulsar client library to create a consumer
        // with the specified topic, subscription, and other configurations.
        if (client == null || client.isClosed()) {
            this.client = createPulsarConnection(configuration);
        }

        ConsumerBuilder<String> consumerBuilder = client.newConsumer(Schema.STRING);

        if (topicNames != null) {
            List<String> list = Arrays.asList(topicNames.split(","));
            consumerBuilder.topics(list);
        } else if (topicsPattern != null) {
            Pattern topicPattern = Pattern.compile(topicsPattern);
            consumerBuilder.topicsPattern(topicPattern);

            if (subscriptionTopicsMode != null) {
                consumerBuilder.subscriptionTopicsMode(subscriptionTopicsMode);
            } else {
                throw new SynapseException("Subscription Topics Mode should be specified when subscribing to a "
                        + "topic using a regular expression (topic pattern).");
            }
        } else {
            throw new SynapseException("Either topicNames or topicsPattern must be specified.");
        }

        if (subscriptionName != null && !subscriptionName.isEmpty()) {
            consumerBuilder.subscriptionName(subscriptionName);
        } else {
            throw new SynapseException("Subscription name should not be empty.");
        }

        if (subscriptionType != null) {
            consumerBuilder.subscriptionType(subscriptionType);
        }

        if (subscriptionInitialPosition != null) {
            consumerBuilder.subscriptionInitialPosition(subscriptionInitialPosition);
        }

        if (consumerName != null && !consumerName.isEmpty()) {
            consumerBuilder.consumerName(consumerName);
        }

        if (ackTimeoutMillis != null) {
            consumerBuilder.ackTimeout(ackTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        if (priorityLevel != null) {
            consumerBuilder.priorityLevel(priorityLevel);
        }

        if (receiverQueueSize != null) {
            consumerBuilder.receiverQueueSize(receiverQueueSize);
        }

        if (maxTotalReceiverQueueSizeAcrossPartitions != null) {
            consumerBuilder.maxTotalReceiverQueueSizeAcrossPartitions(maxTotalReceiverQueueSizeAcrossPartitions);
        }

        if (dlqTopic != null) {
            if (dlqMaxRedeliverCount == null) {
                dlqMaxRedeliverCount = 5;
            }
            DeadLetterPolicy deadLetterPolicy = DeadLetterPolicy.builder()
                    .maxRedeliverCount(dlqMaxRedeliverCount)  // Max retries before dead-lettering
                    .deadLetterTopic(dlqTopic)   // Optional: custom DLT name
                    .build();
            consumerBuilder.deadLetterPolicy(deadLetterPolicy);
        }

        if (batchReceiveEnabled) {
            if (batchingMaxMessages == null && batchingMaxBytes == null && batchingTimeout == null) {
                throw new SynapseException("At least one of maxNumMessages, maxNumBytes, timeout must be specified.");
            }
            BatchReceivePolicy.Builder batchReceivePolicyBuilder = BatchReceivePolicy.builder();

            if (batchingMaxMessages != null) {
                batchReceivePolicyBuilder.maxNumMessages(batchingMaxMessages);
            }
            if (batchingMaxBytes != null) {
                batchReceivePolicyBuilder.maxNumBytes(batchingMaxBytes);
            }
            if (batchingTimeout != null) {
                batchReceivePolicyBuilder.timeout(batchingTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            consumerBuilder.enableBatchIndexAcknowledgment(batchIndexAcknowledgmentEnabled);

            consumerBuilder.batchReceivePolicy(batchReceivePolicyBuilder.build());
        } else {
            if (autoAckOldestChunkedMessageOnQueueFull != null) {
                consumerBuilder.autoAckOldestChunkedMessageOnQueueFull(autoAckOldestChunkedMessageOnQueueFull);
            }
            if (expiryTimeOfIncompleteChunkedMessageMillis != null) {
                consumerBuilder.expireTimeOfIncompleteChunkedMessage(expiryTimeOfIncompleteChunkedMessageMillis,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            if (maxPendingChunkedMessage != null) {
                consumerBuilder.maxPendingChunkedMessage(maxPendingChunkedMessage);
            }
        }

        if (autoUpdatePartitions != null) {
            consumerBuilder.autoUpdatePartitions(autoUpdatePartitions);
        }

        if (autoUpdatePartitionsIntervalSeconds != null) {
            consumerBuilder.autoUpdatePartitionsInterval(autoUpdatePartitionsIntervalSeconds, java.util.concurrent.TimeUnit.SECONDS);
        }

        if (replicateSubscriptionState != null) {
            consumerBuilder.replicateSubscriptionState(replicateSubscriptionState);
        }

        if (readCompacted != null) {
            consumerBuilder.readCompacted(readCompacted);
        }

        if (nackRedeliveryDelayMillis != null) {
            consumerBuilder.negativeAckRedeliveryDelay(nackRedeliveryDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        return consumerBuilder.subscribe();
    }

    private void getConsumerConfigFromProperties(Properties properties) {

        this.topicNames = properties.getProperty(PulsarConstants.TOPIC_NAMES);
        this.topicsPattern = properties.getProperty(PulsarConstants.TOPICS_PATTERN);
        this.subscriptionName = properties.getProperty(PulsarConstants.SUBSCRIPTION_NAME);


        String subscriptionModeString = properties.getProperty(PulsarConstants.SUBSCRIPTION_TOPICS_MODE);
        if (subscriptionModeString != null && !subscriptionModeString.isEmpty()) {
            try {
                this.subscriptionTopicsMode = RegexSubscriptionMode.valueOf(subscriptionModeString);
            } catch (IllegalArgumentException e) {
                throw new SynapseException("Invalid subscription topics mode: " + subscriptionTopicsMode
                        + ". Valid types are: " + Arrays.toString(RegexSubscriptionMode.values()), e);
            }
        }

        String subscriptionTypeString = properties.getProperty(PulsarConstants.SUBSCRIPTION_TYPE);
        if (subscriptionTypeString != null && !subscriptionTypeString.isEmpty()) {
            try {
                this.subscriptionType = SubscriptionType.valueOf(subscriptionTypeString);
            } catch (IllegalArgumentException e) {
                throw new SynapseException("Invalid subscription type: " + subscriptionType
                        + ". Valid types are: " + Arrays.toString(SubscriptionType.values()), e);
            }
        }


        String subscriptionInitialPositionString = properties.getProperty(PulsarConstants.SUBSCRIPTION_INITIAL_POSITION);
        if (subscriptionInitialPositionString != null && !subscriptionInitialPositionString.isEmpty()) {
            try {
                this.subscriptionInitialPosition = SubscriptionInitialPosition.valueOf(subscriptionInitialPositionString);
            } catch (IllegalArgumentException e) {
                throw new SynapseException("Invalid subscription initial position: " + subscriptionInitialPosition
                        + ". Valid types are: " + Arrays.toString(SubscriptionInitialPosition.values()), e);
            }
        }

        this.consumerName = properties.getProperty(PulsarConstants.CONSUMER_NAME);


        String ackTimeoutMillisString = properties.getProperty(PulsarConstants.ACK_TIMEOUT_MILLIS);
        if (ackTimeoutMillisString != null && !ackTimeoutMillisString.isEmpty()) {
            this.ackTimeoutMillis = Long.parseLong(ackTimeoutMillisString);
        }

        String messageWaitTimeoutString = properties.getProperty(PulsarConstants.MESSAGE_WAIT_TIMEOUT);
        if (messageWaitTimeoutString != null && !messageWaitTimeoutString.isEmpty()) {
            this.messageWaitTimeout = Integer.parseInt(messageWaitTimeoutString);
        }

        String nackRedeliveryDelayString = properties.getProperty(PulsarConstants.NACK_REDELIVERY_DELAY);
        if (nackRedeliveryDelayString != null && !nackRedeliveryDelayString.isEmpty()) {
            this.nackRedeliveryDelayMillis = Long.parseLong(nackRedeliveryDelayString);
        }

        String receiverQueueSizeString = properties.getProperty(PulsarConstants.RECEIVER_QUEUE_SIZE);
        if (receiverQueueSizeString != null && !receiverQueueSizeString.isEmpty()) {
            this.receiverQueueSize = Integer.parseInt(receiverQueueSizeString);
        }

        String priorityLevelString = properties.getProperty(PulsarConstants.PRIORITY_LEVEL);
        if (priorityLevelString != null && !priorityLevelString.isEmpty()) {
            this.priorityLevel = Integer.parseInt(priorityLevelString);
        }

        String maxTotalReceiverQueueSizeAcrossPartitionsString = properties.getProperty(
                PulsarConstants.MAX_TOTAL_RECEIVER_QUEUE_SIZE_ACROSS_PARTITIONS);
        if (maxTotalReceiverQueueSizeAcrossPartitionsString != null && !maxTotalReceiverQueueSizeAcrossPartitionsString.isEmpty()) {
            this.maxTotalReceiverQueueSizeAcrossPartitions = Integer.parseInt(maxTotalReceiverQueueSizeAcrossPartitionsString);
        }

        String dlqMaxRedeliverCountString = properties.getProperty(PulsarConstants.DLQ_MAX_REDELIVERY_COUNT);
        if (dlqMaxRedeliverCountString != null && !dlqMaxRedeliverCountString.isEmpty()) {
            this.dlqMaxRedeliverCount = Integer.parseInt(dlqMaxRedeliverCountString);
        }

        this.dlqTopic = properties.getProperty(PulsarConstants.DLQ_TOPIC);

        String autoAckOldestChunkedMessageOnQueueFullString = properties.getProperty(
                PulsarConstants.AUTO_ACK_OLDEST_CHUNKED_MESSAGE_ON_QUEUE_FULL);
        if (autoAckOldestChunkedMessageOnQueueFullString != null && !autoAckOldestChunkedMessageOnQueueFullString.isEmpty()) {
            this.autoAckOldestChunkedMessageOnQueueFull = Boolean.parseBoolean(autoAckOldestChunkedMessageOnQueueFullString);
        }

        String maxPendingChunkedMessageString = properties.getProperty(PulsarConstants.MAX_PENDING_CHUNKED_MESSAGE);
        if (maxPendingChunkedMessageString != null && !maxPendingChunkedMessageString.isEmpty()) {
            this.maxPendingChunkedMessage = Integer.parseInt(maxPendingChunkedMessageString);
        }

        String expiryTimeOfIncompleteChunkedMessageMillisString = properties.getProperty(
                PulsarConstants.EXPIRY_TIME_OF_INCOMPLETE_CHUNKED_MESSAGE_MILLIS);
        if (expiryTimeOfIncompleteChunkedMessageMillisString != null && !expiryTimeOfIncompleteChunkedMessageMillisString.isEmpty()) {
            this.expiryTimeOfIncompleteChunkedMessageMillis = Long.parseLong(expiryTimeOfIncompleteChunkedMessageMillisString);
        }

        String autoUpdatePartitionsString = properties.getProperty(PulsarConstants.AUTO_UPDATE_PARTITIONS);
        if (autoUpdatePartitionsString != null && !autoUpdatePartitionsString.isEmpty()) {
            this.autoUpdatePartitions = Boolean.parseBoolean(autoUpdatePartitionsString);
        }

        String autoUpdatePartitionsIntervalSecondsString = properties.getProperty(
                PulsarConstants.AUTO_UPDATE_PARTITIONS_INTERVAL_SECONDS);
        if (autoUpdatePartitionsIntervalSecondsString != null && !autoUpdatePartitionsIntervalSecondsString.isEmpty()) {
            this.autoUpdatePartitionsIntervalSeconds = Integer.parseInt(autoUpdatePartitionsIntervalSecondsString);
        }

        String replicateSubscriptionStateStr = properties.getProperty(PulsarConstants.REPLICATE_SUBSCRIPTION_STATE);
        this.replicateSubscriptionState = replicateSubscriptionStateStr != null && !replicateSubscriptionStateStr.isEmpty()
                && Boolean.parseBoolean(replicateSubscriptionStateStr);

        String readCompactedStr = properties.getProperty(PulsarConstants.READ_COMPACTED);
        if (readCompactedStr != null && !readCompactedStr.isEmpty()) {
            this.readCompacted = Boolean.parseBoolean(readCompactedStr);
        }

        String batchReceiveEnabledStr = properties.getProperty(PulsarConstants.BATCH_RECEIVE_ENABLED);
        if (batchReceiveEnabledStr != null && !batchReceiveEnabledStr.isEmpty()) {
            this.batchReceiveEnabled = Boolean.parseBoolean(batchReceiveEnabledStr);
        }
        if (this.batchReceiveEnabled) {
            String batchingMaxMessagesStr = properties.getProperty(PulsarConstants.BATCHING_MAX_MESSAGES);
            if (batchingMaxMessagesStr != null && !batchingMaxMessagesStr.isEmpty()) {
                this.batchingMaxMessages = Integer.parseInt(batchingMaxMessagesStr);
            }
            String batchingMaxBytesStr = properties.getProperty(PulsarConstants.BATCHING_MAX_BYTES);
            if (batchingMaxBytesStr != null && !batchingMaxBytesStr.isEmpty()) {
                this.batchingMaxBytes = Integer.parseInt(batchingMaxBytesStr);
            }
            String batchingTimeoutStr = properties.getProperty(PulsarConstants.BATCHING_TIMEOUT);
            if (batchingTimeoutStr != null && !batchingTimeoutStr.isEmpty()) {
                this.batchingTimeout = Integer.parseInt(batchingTimeoutStr);
            }
            String batchIndexAckEnabledStr = properties.getProperty(PulsarConstants.BATCH_INDEX_ACK_ENABLED);
            if (batchIndexAckEnabledStr != null && !batchIndexAckEnabledStr.isEmpty()) {
                this.batchIndexAcknowledgmentEnabled = Boolean.parseBoolean(batchIndexAckEnabledStr);
            }
        }

        String processingMode = properties.getProperty(PulsarConstants.PROCESSING_MODE);

        this.receiveSync = PulsarConstants.SYNC.equalsIgnoreCase(processingMode);

        this.contentType = properties.getProperty(PulsarConstants.CONTENT_TYPE);

    }

    private boolean injectMessage(String strMessage, String contentType, MessageContext msgCtx) {

        AutoCloseInputStream in = new AutoCloseInputStream(new ByteArrayInputStream(strMessage.getBytes()));
        return this.injectMessage(in, contentType, msgCtx);
    }

    private boolean injectMessage(InputStream in, String contentType, MessageContext msgCtx) {
        boolean isConsumed = true;
        try {
            if (log.isDebugEnabled()) {
                log.debug("Processed Custom inbound EP Message of Content-type : " + contentType + " for " + name);
            }

            org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx)
                    .getAxis2MessageContext();
            Object builder;
            if (StringUtils.isEmpty(contentType)) {
                log.warn("Unable to determine content type for message, setting to application/json for " + name);
            }
            int index = contentType.indexOf(';');
            String type = index > 0 ? contentType.substring(0, index) : contentType;
            builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
            if (builder == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No message builder found for type '" + type + "'. Falling back to SOAP. for" + name);
                }
                builder = new SOAPBuilder();
            }

            OMElement documentElement1 = ((Builder) builder).processDocument(in, contentType, axis2MsgCtx);
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement1));
            if (this.injectingSeq == null || "".equals(this.injectingSeq)) {
                log.error("Sequence name not specified. Sequence : " + this.injectingSeq + " for " + name);
                isConsumed = false;
            }

            SequenceMediator seq = (SequenceMediator) this.synapseEnvironment.getSynapseConfiguration().getSequence(
                    this.injectingSeq);
            if (seq == null) {
                throw new SynapseException(
                        "Sequence with name : " + this.injectingSeq + " is not found to mediate the message.");
            }
            seq.setErrorHandler(this.onErrorSeq);
            if (log.isDebugEnabled()) {
                log.debug("injecting message to sequence : " + this.injectingSeq + " of " + name);
            }
            if (!this.synapseEnvironment.injectInbound(msgCtx, seq, this.sequential)) {
                isConsumed = false;
            }
            if (isRollback(msgCtx)) {
                isConsumed = false;
            }
        } catch (Exception e) {
            log.error("Error while processing the Message and the message should be in the "
                    + "format of " + contentType, e);
            isConsumed = false;
        }
        return isConsumed;
    }

    public void destroy() {
        boundedExecutor.shutdownNow();
        if (consumer != null) {
            try {
                consumer.close();
            } catch (PulsarClientException e) {
                log.error("Error while closing Pulsar consumer", e);
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (PulsarClientException e) {
                log.error("Error while closing Pulsar client", e);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("PulsarMessageConsumer destroyed for " + name);
        }
    }

    /**
     * Check the SET_ROLLBACK_ONLY property set to true
     *
     * @param msgCtx SynapseMessageContext
     * @return true or false
     */
    private boolean isRollback(MessageContext msgCtx) {
        // check rollback property from synapse context
        Object rollbackProp = msgCtx.getProperty("SET_ROLLBACK_ONLY");
        if (rollbackProp != null) {
            return (rollbackProp instanceof Boolean && ((Boolean) rollbackProp))
                    || (rollbackProp instanceof String && Boolean.valueOf((String) rollbackProp));
        }
        return false;
    }
}

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

package org.wso2.integration.inbound.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pulsar.client.api.Message;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.wso2.integration.inbound.PulsarMessageConsumer;
import org.wso2.integration.inbound.pojo.ConnectionConfiguration;
import org.wso2.integration.inbound.pojo.JWTAuthConfig;
import org.wso2.integration.inbound.pojo.PulsarConnectionConfig;
import org.wso2.integration.inbound.pojo.PulsarSecureConnectionConfig;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class PulsarUtils {

    private static final Log log = LogFactory.getLog(PulsarUtils.class);

    public static PulsarConnectionConfig getPulsarConnectionConfigFromContext(Properties properties,
                                                                        PulsarConnectionConfig config)
            throws SynapseException {

        if (config == null) {
            config = new PulsarConnectionConfig();
        }

        config.setServiceUrl(properties.getProperty(PulsarConstants.SERVICE_URL));
        config.setOperationTimeoutSeconds(properties.getProperty(PulsarConstants.OPERATION_TIMEOUT_SECONDS));
        config.setStatsIntervalSeconds(properties.getProperty(PulsarConstants.STATS_INTERVAL_SECONDS));
        config.setNumIoThreads(properties.getProperty(PulsarConstants.NUM_IO_THREADS));
        config.setNumListenerThreads(properties.getProperty(PulsarConstants.NUM_LISTENER_THREADS));
        config.setUseTcpNoDelay(properties.getProperty(PulsarConstants.USE_TCP_NO_DELAY));
        config.setRequestTimeoutMs(properties.getProperty(PulsarConstants.REQUEST_TIMEOUT_MS));
        config.setMaxLookupRequest(properties.getProperty(PulsarConstants.MAX_LOOKUP_REQUESTS));
        config.setKeepAliveIntervalSeconds(properties.getProperty(PulsarConstants.KEEP_ALIVE_INTERVAL_SECONDS));
        config.setMaxBackoffIntervalNanos(properties.getProperty(PulsarConstants.MAX_BACKOFF_INTERVAL_NANOS));
        config.setConcurrentLookupRequest(properties.getProperty(PulsarConstants.CONCURRENT_LOOKUP_REQUEST));
        config.setConnectionMaxIdleSeconds(properties.getProperty(PulsarConstants.CONNECTION_MAX_IDLE_SECONDS));
        config.setConnectionTimeoutMs(properties.getProperty(PulsarConstants.CONNECTION_TIMEOUT_MS));
        config.setConnectionsPerBroker(properties.getProperty(PulsarConstants.CONNECTIONS_PER_BROKER));
        config.setEnableBusyWait(properties.getProperty(PulsarConstants.ENABLE_BUSY_WAIT));
        config.setEnableTransaction(properties.getProperty(PulsarConstants.ENABLE_TRANSACTION));
        config.setInitialBackoffIntervalNanos(properties.getProperty(PulsarConstants.INITIAL_BACKOFF_INTERVAL_NANOS));
        config.setListenerName(properties.getProperty(PulsarConstants.LISTENER_NAME));
        config.setLookupTimeoutMs(properties.getProperty(PulsarConstants.LOOKUP_TIMEOUT_MS));
        config.setMaxLookupRedirects(properties.getProperty(PulsarConstants.MAX_LOOKUP_REDIRECTS));
        config.setMaxLookupRequest(properties.getProperty(PulsarConstants.MAX_LOOKUP_REQUEST));
        config.setMaxNumberOfRejectedRequestPerConnection(
                properties.getProperty(PulsarConstants.MAX_NUMBER_OF_REJECTED_REQUEST_PER_CONNECTION));
        config.setMemoryLimitBytes(properties.getProperty(PulsarConstants.MEMORY_LIMIT_BYTES));

        return config;
    }

    public static PulsarSecureConnectionConfig getPulsarSecureConnectionConfigFromContext(Properties properties)
            throws SynapseException {
        PulsarSecureConnectionConfig config = new PulsarSecureConnectionConfig();

        getPulsarConnectionConfigFromContext(properties, config);
        config.setUseTls(properties.getProperty(PulsarConstants.USE_TLS));
        config.setTlsAllowInsecureConnection(properties.getProperty(PulsarConstants.TLS_ALLOW_INSECURE_CONNECTION));
        config.setEnableTlsHostnameVerification(properties.getProperty(PulsarConstants.TLS_HOSTNAME_VERIFICATION_ENABLE));
        config.setTlsTrustCertsFilePath(properties.getProperty(PulsarConstants.TLS_TRUST_CERTS_FILE_PATH));
        config.setTlsProtocols(properties.getProperty(PulsarConstants.TLS_PROTOCOLS));
        config.setTlsCiphers(properties.getProperty(PulsarConstants.TLS_CIPHERS));
        config.setUseKeyStoreTls(properties.getProperty(PulsarConstants.USE_KEY_STORE_TLS));
        config.setTlsTrustStorePath(properties.getProperty(PulsarConstants.TLS_TRUST_STORE_PATH));
        config.setTlsTrustStorePassword(properties.getProperty(PulsarConstants.TLS_TRUST_STORE_PASSWORD));
        config.setTlsTrustStoreType(properties.getProperty(PulsarConstants.TLS_TRUST_STORE_TYPE));
        config.setAutoCertRefreshSeconds(properties.getProperty(PulsarConstants.AUTO_CERT_REFRESH_SECONDS));

        return config;
    }

    public static ConnectionConfiguration getConnectionConfigFromProperties(Properties properties) {
        ConnectionConfiguration configuration = new ConnectionConfiguration();
        configuration.setConnectionName(properties.getProperty(PulsarConstants.CONNECTION_NAME));
        configuration.setUseTlsEncryption(properties.getProperty(PulsarConstants.USE_TLS),
                properties.getProperty(PulsarConstants.SERVICE_URL));
        if (configuration.getUseTlsEncryption()) {
            PulsarSecureConnectionConfig secureConfig = getPulsarSecureConnectionConfigFromContext(properties);
            configuration.setConnectionConfig(secureConfig);
        } else {
            PulsarConnectionConfig connectionConfig = getPulsarConnectionConfigFromContext(properties, null);
            configuration.setConnectionConfig(connectionConfig);
        }

        String authType = properties.getProperty(PulsarConstants.AUTH_TYPE);
        if (authType != null) {
            switch (authType.toUpperCase()) {
                case PulsarConstants.AUTH_JWT:
                    JWTAuthConfig jwtAuthConfig = new JWTAuthConfig();
                    jwtAuthConfig.setToken(properties.getProperty(PulsarConstants.JWT_TOKEN));
                    configuration.setAuthConfig(jwtAuthConfig);
                    break;
                case PulsarConstants.AUTH_OAUTH2:
                    // Handle OAuth2 authentication
                    log.warn("OAuth2 authentication is not supported yet.");
                    break;
                case PulsarConstants.AUTH_TLS:
                    // Handle TLS authentication
                    log.warn("TLS authentication is not supported yet.");
                    break;
                case PulsarConstants.AUTH_NONE:
                    // Handle no authentication
                    break;
                default:
                    throw new SynapseException("Unsupported authentication type: " + authType);
            }
        }

        return configuration;
    }

    /**
     * Set the Pulsar message to the MessageContext
     *
     * @return MessageContext A message context with the record header values
     */
    public static MessageContext populateMessageContext(Message<String> msg, SynapseEnvironment synapseEnvironment) {
        MessageContext msgCtx = createMessageContext(synapseEnvironment);
        msgCtx.setProperty(PulsarConstants.TOPIC_NAMES, msg.getTopicName());
        msgCtx.setProperty(PulsarConstants.MSG_ID, msg.getMessageId().toString());
        msgCtx.setProperty(PulsarConstants.KEY, msg.getKey());
        msgCtx.setProperty(PulsarConstants.REDELIVERY_COUNT, msg.getRedeliveryCount());
        msgCtx.setProperty(PulsarConstants.PROPERTIES, convertPropertiesToJsonArray(msg.getProperties()).toString());

        return msgCtx;
    }

    /**
     * Create the message context.
     */
    public static MessageContext createMessageContext(SynapseEnvironment synapseEnvironment) {

        MessageContext msgCtx = synapseEnvironment.createMessageContext();
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(String.valueOf(UUID.randomUUID()));
        return msgCtx;
    }

    public static ArrayNode convertPropertiesToJsonArray(Map<String, String> properties) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();

        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                ObjectNode obj = mapper.createObjectNode();
                obj.put(entry.getKey(), entry.getValue());
                arrayNode.add(obj);
            }
        }

        return arrayNode;
    }

}

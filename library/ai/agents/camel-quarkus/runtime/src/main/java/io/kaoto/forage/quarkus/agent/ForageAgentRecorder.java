package io.kaoto.forage.quarkus.agent;

import org.apache.camel.CamelContext;
import org.apache.camel.component.langchain4j.agent.api.Agent;
import org.jboss.logging.Logger;
import io.kaoto.forage.agent.AgentConfig;
import io.kaoto.forage.agent.AgentCreator;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Quarkus recorder that creates Agent beans at runtime.
 *
 * <p>Looks up a ChatModel from the CamelContext registry (placed there by quarkus-langchain4j
 * via CDI and property translation), then the default quarkus-langchain4j ChatModel bean if one was requested
 * at build time, then composes the Agent with memory, guardrails, and RAG via {@link AgentCreator}.
 *
 * @since 1.1
 */
@Recorder
public class ForageAgentRecorder {
    private static final Logger LOG = Logger.getLogger(ForageAgentRecorder.class);

    public RuntimeValue<Agent> createAgent(String name, RuntimeValue<CamelContext> ctx, boolean quarkusChatModel) {
        CamelContext camelContext = ctx.getValue();
        AgentConfig config = AgentCreator.DEFAULT_AGENT.equals(name) ? new AgentConfig() : new AgentConfig(name);
        ClassLoader cl = camelContext.getApplicationContextClassLoader();

        // Try to look up ChatModel from registry (created by quarkus-langchain4j via property translation)
        ChatModel chatModel = camelContext.getRegistry().lookupByNameAndType(name, ChatModel.class);
        if (chatModel == null && quarkusChatModel) {
            // quarkus-langchain4j registers its default ChatModel with @Default only, not under a name
            InstanceHandle<ChatModel> handle = Arc.container().instance(ChatModel.class);
            if (handle.isAvailable()) {
                LOG.infof("Using the default quarkus-langchain4j ChatModel for Agent '%s'", name);
                chatModel = handle.get();
            }
        }
        Agent agent;
        if (chatModel != null) {
            LOG.infof("Found ChatModel for '%s', composing Agent with it", name);
            agent = AgentCreator.createAgent(config, name, cl, chatModel);
        } else {
            LOG.infof("No ChatModel '%s' in registry, creating Agent via ServiceLoader", name);
            agent = AgentCreator.createAgent(config, name, cl);
        }

        if (agent == null) {
            throw new IllegalStateException(
                    ("Forage Agent '%s' could not be created: no ChatModel was found in the registry and no suitable "
                                    + "ModelProvider was resolved via ServiceLoader. Ensure a Forage model artifact "
                                    + "(e.g. forage-model-open-ai, forage-model-ollama) is on the classpath and the "
                                    + "'forage.*' model properties for '%s' are configured.")
                            .formatted(name, name));
        }
        return new RuntimeValue<>(agent);
    }
}

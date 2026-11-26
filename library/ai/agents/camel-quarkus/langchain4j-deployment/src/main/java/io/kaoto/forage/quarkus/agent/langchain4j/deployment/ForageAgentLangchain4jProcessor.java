package io.kaoto.forage.quarkus.agent.langchain4j.deployment;

import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import io.kaoto.forage.quarkus.agent.deployment.ForageAgentBuildItem;
import io.kaoto.forage.quarkus.agent.deployment.ForageAgentQuarkusChatModelBuildItem;
import io.quarkiverse.langchain4j.deployment.RequestChatModelBeanBuildItem;
import io.quarkiverse.langchain4j.deployment.config.LangChain4jBuildConfig;
import io.quarkiverse.langchain4j.deployment.items.ChatModelProviderCandidateBuildItem;
import io.quarkiverse.langchain4j.runtime.NamedConfigUtil;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import dev.langchain4j.model.chat.ChatModel;

public class ForageAgentLangchain4jProcessor {

    private static final Logger LOG = Logger.getLogger(ForageAgentLangchain4jProcessor.class);

    /**
     * quarkus-langchain4j implements AI services at build time, from the classes in the index. Without this, the
     * Forage agent's {@code AiServices} call fails at runtime.
     */
    @BuildStep
    IndexDependencyBuildItem indexForageAgent() {
        return new IndexDependencyBuildItem("io.kaoto.forage", "forage-agent");
    }

    /**
     * quarkus-langchain4j creates a {@code ChatModel} bean only when something injects it. The Forage agent looks
     * the model up at runtime, so request the default model, the one the agent properties are translated to,
     * and keep it although nothing injects it.
     * Same guard as Camel Quarkus {@code SupportQuarkusLangchain4jChatProcessor}: only when the provider is known
     * and the application has no {@code ChatModel} bean of its own.
     */
    @BuildStep
    void requestDefaultChatModel(
            List<ForageAgentBuildItem> agents,
            List<ChatModelProviderCandidateBuildItem> candidates,
            BeanDiscoveryFinishedBuildItem beanDiscoveryFinished,
            LangChain4jBuildConfig buildConfig,
            BuildProducer<RequestChatModelBeanBuildItem> requestChatModelBean,
            BuildProducer<UnremovableBeanBuildItem> unremovable,
            BuildProducer<ForageAgentQuarkusChatModelBuildItem> quarkusChatModel) {

        if (agents.isEmpty()
                || !beanDiscoveryFinished
                        .beanStream()
                        .withBeanType(ChatModel.class)
                        .collect()
                        .isEmpty()) {
            return;
        }

        List<String> providers = candidates.stream()
                .map(ChatModelProviderCandidateBuildItem::getProvider)
                .toList();
        Optional<String> configuredProvider =
                buildConfig.defaultConfig().chatModel().provider();
        boolean providerResolvable =
                configuredProvider.isPresent() ? providers.contains(configuredProvider.get()) : providers.size() == 1;
        if (!providerResolvable) {
            LOG.infof(
                    "Not requesting the default quarkus-langchain4j ChatModel for the Forage agent: provider not"
                            + " determinable from %s. Set quarkus.langchain4j.chat-model.provider",
                    providers);
            return;
        }

        requestChatModelBean.produce(new RequestChatModelBeanBuildItem(NamedConfigUtil.DEFAULT_NAME));
        // Looked up programmatically by the recorder, so ArC must not remove it as unused
        unremovable.produce(UnremovableBeanBuildItem.beanTypes(ChatModel.class));
        quarkusChatModel.produce(new ForageAgentQuarkusChatModelBuildItem());
    }
}

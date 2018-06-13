package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.logging.Logger;

import static hudson.slaves.NodeProvisioner.Strategy;
import static hudson.slaves.NodeProvisioner.StrategyDecision;
import static hudson.slaves.NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
import static hudson.slaves.NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

/**
 * Based on https://github.com/jenkinsci/one-shot-executor-plugin/blob/master/src/main/java/org/jenkinsci/plugins/oneshot/OneShotProvisionerStrategy.java
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class FastNodeProvisionerStrategy extends Strategy {

    private static final Logger LOGGER = Logger.getLogger(FastNodeProvisionerStrategy.class.getName());

    @Nonnull
    @Override
    public StrategyDecision apply(@Nonnull NodeProvisioner.StrategyState state) {
        if (Jenkins.getInstance().isQuietingDown()) {
            return CONSULT_REMAINING_STRATEGIES;
        }


        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof EC2Cloud) {
                final StrategyDecision decision = ApplyForCloud(state, (EC2Cloud) cloud);
                if (decision == PROVISIONING_COMPLETED) return decision;
            }
        }
        return CONSULT_REMAINING_STRATEGIES;
    }

    private StrategyDecision ApplyForCloud(@Nonnull NodeProvisioner.StrategyState state, EC2Cloud cloud) {

        final Label label = state.getLabel();

        if (!cloud.canProvision(label)) {
            return CONSULT_REMAINING_STRATEGIES;
        }

        LoadStatistics.LoadStatisticsSnapshot snapshot = state.getSnapshot();
        LOGGER.log(FINEST, "label={0}, Available executors={1}, connecting={2}, planned={3}",
                new Object[]{label.toString(), snapshot.getAvailableExecutors(), snapshot.getConnectingExecutors(), state.getPlannedCapacitySnapshot()});
        int availableCapacity =
                snapshot.getAvailableExecutors()
                        + snapshot.getConnectingExecutors();

        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(FINE, "label={0}, Available capacity={1}, currentDemand={2}",
                new Object[]{label.toString(), availableCapacity, currentDemand});

        if (availableCapacity < currentDemand) {
            Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(label, currentDemand - availableCapacity);
            LOGGER.log(FINE, "label={0}, Planned {1} new nodes", new Object[]{label.toString(), plannedNodes.size()});
            state.recordPendingLaunches(plannedNodes);
            availableCapacity += plannedNodes.size();
            LOGGER.log(FINE, "label={0} After provisioning, available capacity={1}, currentDemand={2}",
                    new Object[]{label.toString(), availableCapacity, currentDemand});
        }

        if (availableCapacity >= currentDemand) {
            LOGGER.log(FINE, "label={0}, Provisioning completed", label.toString());
            return PROVISIONING_COMPLETED;
        } else {
            LOGGER.log(FINE, "label={0}, Provisioning not complete, consulting remaining strategies", label.toString());
            return CONSULT_REMAINING_STRATEGIES;
        }
    }
}

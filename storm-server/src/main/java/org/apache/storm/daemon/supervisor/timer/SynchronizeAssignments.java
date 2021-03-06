/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.daemon.supervisor.timer;

import java.util.HashMap;
import java.util.Map;

import org.apache.storm.cluster.IStormClusterState;
import org.apache.storm.daemon.supervisor.ReadClusterState;
import org.apache.storm.daemon.supervisor.Supervisor;
import org.apache.storm.generated.Assignment;
import org.apache.storm.generated.SupervisorAssignments;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.NimbusClient;
import org.apache.storm.utils.Time;
import org.apache.storm.utils.Utils;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A runnable which will synchronize assignments to node local and then worker processes.
 */
public class SynchronizeAssignments implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(SynchronizeAssignments.class);

    private Supervisor supervisor;
    private SupervisorAssignments assignments;
    private ReadClusterState readClusterState;

    /**
     * Constructor.
     * @param supervisor {@link Supervisor}
     * @param assignments {@link SupervisorAssignments}
     * @param readClusterState {@link ReadClusterState}
     */
    public SynchronizeAssignments(Supervisor supervisor, SupervisorAssignments assignments, ReadClusterState readClusterState) {
        this.supervisor = supervisor;
        this.assignments = assignments;
        this.readClusterState = readClusterState;
    }

    @Override
    public void run() {
        // first sync assignments to local, then sync processes.
        if (null == assignments) {
            getAssignmentsFromMaster(this.supervisor.getConf(), this.supervisor.getStormClusterState(), this.supervisor.getAssignmentId());
        } else {
            assignedAssignmentsToLocal(this.supervisor.getStormClusterState(), assignments);
        }
        this.readClusterState.run();
    }

    /**
     * Used by {@link Supervisor} to fetch assignments when start up.
     * @param supervisor {@link Supervisor}
     */
    public void getAssignmentsFromMasterUntilSuccess(Supervisor supervisor) {
        boolean success = false;
        while (!success) {
            try (NimbusClient master = NimbusClient.getConfiguredClient(supervisor.getConf())) {
                SupervisorAssignments assignments = master.getClient().getSupervisorAssignments(supervisor.getAssignmentId());
                assignedAssignmentsToLocal(supervisor.getStormClusterState(), assignments);
                success = true;
            } catch (Exception t) {
                // just ignore the exception
            }
            if (!success) {
                LOG.info("Waiting for a success sync of assignments from master...");
                try {
                    Time.sleep(5000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }

    }

    /**
     * Used by {@link Supervisor} to fetch assignments when start up.
     * @param conf config
     * @param clusterState {@link IStormClusterState}
     * @param node id of node
     */
    public void getAssignmentsFromMaster(Map conf, IStormClusterState clusterState, String node) {
        if (ConfigUtils.isLocalMode(conf)) {
            try {
                SupervisorAssignments assignments = this.supervisor.getLocalNimbus().getSupervisorAssignments(node);
                assignedAssignmentsToLocal(clusterState, assignments);
            } catch (TException e) {
                LOG.error("Get assignments from local master exception", e);
            }
        } else {
            try (NimbusClient master = NimbusClient.getConfiguredClient(conf)) {
                SupervisorAssignments assignments = master.getClient().getSupervisorAssignments(node);
                LOG.debug("Sync an assignments from master, will start to sync with assignments: {}", assignments);
                assignedAssignmentsToLocal(clusterState, assignments);
            } catch (Exception t) {
                LOG.error("Get assignments from master exception", t);
            }
        }
    }

    private static void assignedAssignmentsToLocal(IStormClusterState clusterState, SupervisorAssignments assignments) {
        if (null == assignments) {
            //unknown error, just skip
            return;
        }
        Map<String, byte[]> serAssignments = new HashMap<>();
        for (Map.Entry<String, Assignment> entry : assignments.get_storm_assignment().entrySet()) {
            serAssignments.put(entry.getKey(), Utils.serialize(entry.getValue()));
        }
        clusterState.syncRemoteAssignments(serAssignments);
    }
}

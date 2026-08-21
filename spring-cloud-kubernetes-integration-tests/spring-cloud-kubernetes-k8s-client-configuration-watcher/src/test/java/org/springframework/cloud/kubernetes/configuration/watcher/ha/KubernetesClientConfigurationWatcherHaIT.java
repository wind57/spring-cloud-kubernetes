/*
 * Copyright 2013-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.kubernetes.configuration.watcher.ha;

import org.junit.jupiter.api.Test;
import org.testcontainers.k3s.K3sContainer;

import org.springframework.cloud.kubernetes.integration.tests.commons.Commons;
import org.springframework.cloud.kubernetes.integration.tests.commons.k3s.NativeClientIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the initial Kubernetes-client configuration watcher HA wiring.
 *
 * @author wind57
 */
@NativeClientIntegrationTest(withImages = { "spring-cloud-kubernetes-configuration-watcher" },
		rbacNamespaces = "default",
		configurationWatcher = @NativeClientIntegrationTest.ConfigurationWatcher(enabled = true, enableHa = true,
				refreshDelay = "0", reloadEnabled = false))
class KubernetesClientConfigurationWatcherHaIT {

	private static final String CONFIGURATION_WATCHER_APP = "spring-cloud-kubernetes-configuration-watcher";

	private static final String LEADER_ELECTION_LEASE = "spring-k8s-leader-election-lock";

	private static final String CONFIGURATION_WATCHER_STATE_LEASE = "configuration-watcher-ha";

	/**
	 * <pre>
	 *     - start one configuration watcher with HA enabled
	 *     - wait until it becomes leader and creates the HA state lease
	 *     - verify that both the leader-election and state leases exist
	 * </pre>
	 */
	@Test
	void startsAsLeaderAndCreatesHaStateLease(K3sContainer container) throws Exception {
		Commons.waitForLogStatement("Creating watcher HA lease with name : " + CONFIGURATION_WATCHER_STATE_LEASE,
				container, CONFIGURATION_WATCHER_APP);

		String leases = container
			.execInContainer("kubectl", "get", "lease", LEADER_ELECTION_LEASE, CONFIGURATION_WATCHER_STATE_LEASE,
					"--namespace", "default", "--output", "name")
			.getStdout();

		assertThat(leases).contains("lease.coordination.k8s.io/" + LEADER_ELECTION_LEASE,
				"lease.coordination.k8s.io/" + CONFIGURATION_WATCHER_STATE_LEASE);
	}

}

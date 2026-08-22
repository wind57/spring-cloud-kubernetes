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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.testcontainers.k3s.K3sContainer;

import org.springframework.cloud.kubernetes.integration.tests.commons.Awaitilities;
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
				replicas = 2, refreshDelay = "0", reloadEnabled = false))
class KubernetesClientConfigurationWatcherHaIT {

	/**
	 * <pre>
	 *     - start two configuration watcher replicas with HA enabled
	 *     - wait until both replicas are running
	 *     - verify that exactly one replica holds the leader-election lease
	 * </pre>
	 */
	@Test
	void startsTwoReplicasWithSingleLeader(K3sContainer container) {
		Awaitilities.awaitUntilAsserted(120, 1000, () -> {
			try {
				List<String> runningPods = runningPods(container);
				assertThat(runningPods).hasSize(2);

				// we have two replicas, one is the HA leader
				String holderIdentity = container
					.execInContainer("sh", "-c",
							"kubectl get lease -n default spring-k8s-leader-election-lock"
									+ " -o jsonpath='{.spec.holderIdentity}'")
					.getStdout()
					.trim();

				assertThat(holderIdentity).isNotBlank();
				assertThat(runningPods).contains(holderIdentity);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * get both pods as part of the replica of the deployment.
	 */
	private List<String> runningPods(K3sContainer container) {
		try {
			String runningPods = container
				.execInContainer("sh", "-c",
					"kubectl get pods -n default -l app=spring-cloud-kubernetes-configuration-watcher"
						+ " --field-selector=status.phase=Running"
						+ " -o jsonpath='{.items[*].metadata.name}'")
				.getStdout()
				.trim();

			return runningPods.isEmpty() ? List.of()
				: Arrays.stream(runningPods.split("\\s+")).toList();

		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

}

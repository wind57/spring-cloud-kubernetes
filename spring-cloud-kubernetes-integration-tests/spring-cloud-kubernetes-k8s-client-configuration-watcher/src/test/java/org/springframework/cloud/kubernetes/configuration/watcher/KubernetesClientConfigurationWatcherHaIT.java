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

package org.springframework.cloud.kubernetes.configuration.watcher;

import java.util.Arrays;
import java.util.List;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.k3s.K3sContainer;

import org.springframework.cloud.kubernetes.integration.tests.commons.Awaitilities;
import org.springframework.cloud.kubernetes.integration.tests.commons.k3s.NativeClientIntegrationTest;
import org.springframework.cloud.kubernetes.integration.tests.commons.native_client.NativeClientKubernetesFixture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the initial Kubernetes-client configuration watcher HA wiring.
 *
 * @author wind57
 */
@NativeClientIntegrationTest(withImages = { "spring-cloud-kubernetes-configuration-watcher" },
		wiremock = @NativeClientIntegrationTest.Wiremock(enabled = true, namespaces = "default", withNodePort = true),
		rbacNamespaces = "default",
		configurationWatcher = @NativeClientIntegrationTest.ConfigurationWatcher(enabled = true, enableHa = true,
				replicas = 2, refreshDelay = "0", reloadEnabled = false))
class KubernetesClientConfigurationWatcherHaIT {

	@BeforeAll
	static void beforeAll(NativeClientKubernetesFixture fixture) {
		TestUtil.configureWireMock();
		TestUtil.createConfigMap(fixture, "default");
	}

	@AfterAll
	static void afterAll(NativeClientKubernetesFixture fixture) {
		TestUtil.deleteConfigMap(fixture, "default");
	}

	/**
	 * <pre>
	 *     - start two configuration watcher replicas with HA enabled
	 *     - wait until both replicas are running
	 *     - verify that exactly one replica holds the leader-election lease
	 *     - wait until the initial ConfigMap resource version is stored in the HA Lease
	 *     - update the ConfigMap once
	 *     - verify that the change triggers exactly one actuator refresh
	 *     - verify that the updated resource version is stored in the HA Lease
	 * </pre>
	 */
	@Test
	void persistsResourceVersionAndTriggersRefreshWithTwoReplicas(K3sContainer container) {

		// 1. we have two replicas running
		// 2. only one is the HA leader
		Awaitilities.awaitUntilAsserted(120, 1000, () -> {
			try {
				List<String> runningPods = runningPods(container);
				assertThat(runningPods).hasSize(2);

				// we have two replicas, one is the HA leader
				String exec = """
					kubectl get lease -n default spring-k8s-leader-election-lock \\
						-o "jsonpath={.spec.holderIdentity}"
					""";

				String holderIdentity = container
					.execInContainer("sh", "-c", exec)
					.getStdout()
					.trim();

				assertThat(holderIdentity).isNotBlank();
				assertThat(runningPods).contains(holderIdentity);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		// 3. resource version of the configmap is present in out store
		String initialResourceVersion = configMapResourceVersion(container);
		Awaitilities.awaitUntilAsserted(120, 1000,
			() -> assertThat(configMapResourceVersionInStateLease(container))
				.isEqualTo(initialResourceVersion));

		// 4. once we update the configmap, resourceVersion changes, and we have it in out store.
		WireMock.resetAllRequests();
		patchConfigMap(container);
		String updatedResourceVersion = configMapResourceVersion(container);

		// 5. because of the update in the configmap, watcher caught that and sent a
		// refresh call to the actuator ( wiremock in our test )
		TestUtil.verifyActuatorCalled(1);

		// 6. the new resourceVersion is not equal to the previous one
		// 7. we have the latest resourceVersion in our store
		Awaitilities.awaitUntilAsserted(120, 1000,
				() -> {
					String afterPatchResourceVersion = configMapResourceVersionInStateLease(container);
					assertThat(afterPatchResourceVersion).isNotEqualTo(initialResourceVersion);
					assertThat(afterPatchResourceVersion).isEqualTo(updatedResourceVersion);
				});
	}

	/**
	 * get both pods as part of the replica of the deployment.
	 */
	private List<String> runningPods(K3sContainer container) {

		String exec = """
			kubectl get pods -n default -l app=spring-cloud-kubernetes-configuration-watcher \\
				--field-selector=status.phase=Running \\
				-o "jsonpath={.items[*].metadata.name}"
			""";

		try {
			String runningPods = container
				.execInContainer("sh", "-c", exec)
				.getStdout()
				.trim();

			return runningPods.isEmpty() ? List.of()
				: Arrays.stream(runningPods.split("\\s+")).toList();

		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * return the resourceVersion from the 'service-wiremock' configmap.
	 */
	private String configMapResourceVersion(K3sContainer container) {

		String exec = """
			kubectl get configmap service-wiremock -n default \\
				-o "jsonpath={.metadata.resourceVersion}"
			""";

		try {
			return container
				.execInContainer("sh", "-c", exec)
				.getStdout()
				.trim();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String configMapResourceVersionInStateLease(K3sContainer container) {

		String exec = """
			kubectl get lease -n default configuration-watcher-ha \\
				-o "jsonpath={.metadata.annotations['spring\\.cloud\\.kubernetes\\.configuration\\.watcher/configmap-resource-version']}"
			""";

		try {
			// default=123
			String storedResourceVersion = container
				.execInContainer("sh", "-c", exec)
				.getStdout()
				.trim();
			// get only the 123 part
			return storedResourceVersion.substring("default=".length());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void patchConfigMap(K3sContainer container) {
		String exec = """
			kubectl patch configmap service-wiremock -n default --type merge \\
				-p '{"data":{"foo":"updated"}}'
			""";

		try {
			container.execInContainer("sh", "-c", exec);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}

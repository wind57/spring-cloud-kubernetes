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

import java.util.HashMap;
import java.util.Map;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoordinationV1Api;
import io.kubernetes.client.openapi.models.V1Lease;
import io.kubernetes.client.openapi.models.V1ObjectMeta;

import org.springframework.core.log.LogAccessor;
import org.springframework.util.StringUtils;

/**
 * Lease-backed persistent store for watcher HA state.
 *
 * <p>
 * All methods in this class are expected to be called only by the watcher instance that
 * currently holds leadership. Follower instances must not read or write HA state through
 * this store.
 *
 * @author wind57
 */
final class LeaseConfigurationWatcherStateStore implements ConfigurationWatcherStateStore {

	private static final LogAccessor LOG = new LogAccessor(LeaseConfigurationWatcherStateStore.class);

	private static final String CONFIGMAP_ANNOTATION = "spring.cloud.kubernetes.configuration.watcher.configmap-resource-version";

	private static final String SECRET_ANNOTATION = "spring.cloud.kubernetes.configuration.watcher.secret-resource-version";

	private final CoordinationV1Api api;

	private final ConfigurationWatcherHaProperties properties;

	LeaseConfigurationWatcherStateStore(CoordinationV1Api api, ConfigurationWatcherHaProperties properties) {
		this.api = api;
		this.properties = properties;
	}

	@Override
	public ConfigurationWatcherState readOrCreate() {

		String leaseName = properties.getLeaseName();
		String leaseNamespace = namespace();
		LOG.debug("Reading lease with name: " + leaseName + " in namespace: " + leaseNamespace);

		try {
			V1Lease lease = api.readNamespacedLease(leaseName, leaseNamespace).execute();
			Map<String, String> annotations = existingAnnotations(lease);
			return new ConfigurationWatcherState(annotations.get(CONFIGMAP_ANNOTATION),
					annotations.get(SECRET_ANNOTATION));
		}
		catch (ApiException e) {
			if (e.getCode() == 404) {
				createLease(leaseName, leaseNamespace);
				return ConfigurationWatcherState.EMPTY;
			}
			throw new IllegalStateException("Failed to read watcher HA lease '" + properties.getLeaseName()
					+ "' in namespace '" + leaseNamespace + "'", e);
		}
	}

	@Override
	public void write(ConfigurationWatcherState state) {
		try {

			String leaseName = properties.getLeaseName();
			String leaseNamespace = namespace();
			LOG.debug("Updating lease with name: " + leaseName + " in namespace: " + leaseNamespace);

			V1Lease currentLease = api.readNamespacedLease(leaseName, leaseNamespace).execute();
			// add the annotations
			V1Lease updatedLease = updatedLease(currentLease, state);
			api.replaceNamespacedLease(leaseName, leaseNamespace, updatedLease).execute();
		}
		catch (ApiException e) {
			LOG.error(e, () -> "Failed to write to the lease because : " + e.getResponseBody());
			throw new IllegalStateException("Failed to write watcher HA lease '" + properties.getLeaseName()
					+ "' in namespace '" + namespace() + "'", e);
		}
	}

	private void createLease(String leaseName, String leaseNamespace) {
		try {
			LOG.info(() -> "Creating watcher HA lease with name : " + leaseName + " in namespace : " + leaseNamespace);
			api.createNamespacedLease(leaseNamespace, newLease(leaseName, leaseNamespace)).execute();
		}
		catch (ApiException e) {
			LOG.error(e, () -> "Failed to create watcher HA lease '" + e.getResponseBody());
			throw new IllegalStateException("Failed to create watcher HA lease '" + properties.getLeaseName()
					+ "' in namespace '" + namespace() + "'", e);
		}
	}

	private V1Lease updatedLease(V1Lease lease, ConfigurationWatcherState state) {
		V1ObjectMeta metadata = lease.getMetadata();
		Map<String, String> currentLeaseAnnotations = existingAnnotations(lease);
		metadata.setAnnotations(updateAnnotations(currentLeaseAnnotations, state));
		return lease;
	}

	private V1Lease newLease(String leaseName, String leaseNamespace) {
		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.setName(leaseName);
		metadata.setNamespace(leaseNamespace);
		return new V1Lease().metadata(metadata);
	}

	private static Map<String, String> existingAnnotations(V1Lease lease) {
		if (lease.getMetadata() == null || lease.getMetadata().getAnnotations() == null) {
			LOG.warn(() -> "Lease has no annotations");
			return Map.of();
		}
		return lease.getMetadata().getAnnotations();
	}

	private static Map<String, String> updateAnnotations(Map<String, String> existingAnnotations,
			ConfigurationWatcherState state) {
		Map<String, String> annotations = new HashMap<>(existingAnnotations);

		String configMapResourceVersion = state.configMapResourceVersion();
		if (configMapResourceVersion != null) {
			annotations.put(CONFIGMAP_ANNOTATION, configMapResourceVersion);
		}

		String secretResourceVersion = state.secretResourceVersion();
		if (secretResourceVersion != null) {
			annotations.put(SECRET_ANNOTATION, secretResourceVersion);
		}

		return annotations;
	}

	private String namespace() {
		String leaseNamespaceFromProperties = properties.getLeaseNamespace();
		return StringUtils.hasText(leaseNamespaceFromProperties) ? leaseNamespaceFromProperties : "default";
	}

}

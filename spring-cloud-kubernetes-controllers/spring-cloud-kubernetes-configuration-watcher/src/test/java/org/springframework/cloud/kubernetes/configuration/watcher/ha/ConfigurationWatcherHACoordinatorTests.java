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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.kubernetes.client.config.reload.KubernetesClientEventBasedConfigMapChangeDetector;
import org.springframework.cloud.kubernetes.client.config.reload.KubernetesClientEventBasedSecretsChangeDetector;
import org.springframework.cloud.kubernetes.commons.leader.election.events.StartLeadingEvent;
import org.springframework.cloud.kubernetes.commons.leader.election.events.StopLeadingEvent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author wind57
 */
class ConfigurationWatcherHACoordinatorTests {

	@Test
	void onStartLeadingStartsBothDetectors() {
		KubernetesClientEventBasedConfigMapChangeDetector configMapDetector = mock(
				KubernetesClientEventBasedConfigMapChangeDetector.class);

		KubernetesClientEventBasedSecretsChangeDetector secretsDetector = mock(
				KubernetesClientEventBasedSecretsChangeDetector.class);

		ObjectProvider<KubernetesClientEventBasedConfigMapChangeDetector> configMapProvider = provider(
				KubernetesClientEventBasedConfigMapChangeDetector.class, configMapDetector);

		ObjectProvider<KubernetesClientEventBasedSecretsChangeDetector> secretsProvider = provider(
				KubernetesClientEventBasedSecretsChangeDetector.class, secretsDetector);

		ConfigurationWatcherHACoordinator coordinator = new ConfigurationWatcherHACoordinator(configMapProvider,
				secretsProvider);

		coordinator.onStartLeading(new StartLeadingEvent("candidate"));

		verify(configMapDetector).start();
		verify(secretsDetector).start();
	}

	@Test
	void onStopLeadingStopsBothDetectors() {
		KubernetesClientEventBasedConfigMapChangeDetector configMapDetector = mock(
				KubernetesClientEventBasedConfigMapChangeDetector.class);

		KubernetesClientEventBasedSecretsChangeDetector secretsDetector = mock(
				KubernetesClientEventBasedSecretsChangeDetector.class);

		ObjectProvider<KubernetesClientEventBasedConfigMapChangeDetector> configMapProvider = provider(
				KubernetesClientEventBasedConfigMapChangeDetector.class, configMapDetector);

		ObjectProvider<KubernetesClientEventBasedSecretsChangeDetector> secretsProvider = provider(
				KubernetesClientEventBasedSecretsChangeDetector.class, secretsDetector);

		ConfigurationWatcherHACoordinator coordinator = new ConfigurationWatcherHACoordinator(configMapProvider,
				secretsProvider);

		coordinator.onStopLeading(new StopLeadingEvent("candidate"));

		verify(configMapDetector).stop();
		verify(secretsDetector).stop();
	}

	@Test
	void failsWhenNeitherDetectorIsAvailable() {
		ObjectProvider<KubernetesClientEventBasedConfigMapChangeDetector> configMapProvider = emptyProvider(
				KubernetesClientEventBasedConfigMapChangeDetector.class);
		ObjectProvider<KubernetesClientEventBasedSecretsChangeDetector> secretsProvider = emptyProvider(
				KubernetesClientEventBasedSecretsChangeDetector.class);

		assertThatThrownBy(() -> new ConfigurationWatcherHACoordinator(configMapProvider, secretsProvider))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Configuration watcher HA is enabled, but neither ConfigMap nor Secret watching is enabled");
	}

	private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("detector", value);
		return beanFactory.getBeanProvider(type);
	}

	private static <T> ObjectProvider<T> emptyProvider(Class<T> type) {
		return new DefaultListableBeanFactory().getBeanProvider(type);
	}

}

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

package org.springframework.cloud.kubernetes.client.config.reload;

import java.util.Map;

import io.kubernetes.client.common.KubernetesObject;

import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.cloud.kubernetes.commons.config.reload.ConfigReloadUtil;
import org.springframework.cloud.kubernetes.commons.config.reload.ConfigReloadProperties;
import org.springframework.cloud.kubernetes.commons.config.reload.ConfigurationChangeDetector;
import org.springframework.cloud.kubernetes.commons.config.reload.ConfigurationUpdateStrategy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.log.LogAccessor;

/**
 * @author wind57
 */
abstract class KubernetesClientEventBasedChangeDetector extends ConfigurationChangeDetector {

	private static final LogAccessor LOG = new LogAccessor(KubernetesClientEventBasedChangeDetector.class);

	private final String target;

	private final PropertySourceLocator propertySourceLocator;

	private final ConfigurableEnvironment environment;

	private final Class<? extends MapPropertySource> existingSourcesType;

	protected KubernetesClientEventBasedChangeDetector(ConfigurationUpdateStrategy strategy, String target,
			PropertySourceLocator propertySourceLocator, ConfigurableEnvironment environment,
			Class<? extends MapPropertySource> existingSourcesType) {
		super(strategy);
		this.target = target;
		this.propertySourceLocator = propertySourceLocator;
		this.environment = environment;
		this.existingSourcesType = existingSourcesType;
	}

	protected final Map<String, String> resolveLabelSelector(boolean enableReloadFiltering,
			Map<String, String> configuredLabels, String replacementProperty) {

		if (!enableReloadFiltering) {
			return configuredLabels;
		}

		LOG.warn(() -> "enable reload filtering is deprecated and will be removed in the next major release");
		LOG.warn(() -> "use " + replacementProperty + " instead");

		if (!configuredLabels.isEmpty()) {
			LOG.warn(() -> replacementProperty + " is not empty, but "
					+ "spring.cloud.kubernetes.reload.enable-reload-filtering is enabled and will override it");
		}

		return Map.of(ConfigReloadProperties.RELOAD_LABEL_FILTER, "true");
	}

	protected final void onEvent(KubernetesObject resource) {
		boolean reload = ConfigReloadUtil.reload(target, resource.toString(), propertySourceLocator, environment,
				existingSourcesType);
		if (reload) {
			reloadProperties();
		}
	}

}

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

import java.util.function.BiPredicate;
import java.util.function.Consumer;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.ResourceEventHandler;

import org.springframework.core.log.LogAccessor;

/**
 * @param <T> either a configmap or a secret
 * @author wind57
 */
final class KubernetesResourceEventHandler<T extends KubernetesObject> implements ResourceEventHandler<T> {

	private static final LogAccessor LOG = new LogAccessor(KubernetesResourceEventHandler.class);

	private final BiPredicate<T, T> dataEquals;

	private final Consumer<T> onEvent;

	KubernetesResourceEventHandler(BiPredicate<T, T> dataEquals, Consumer<T> onEvent) {
		this.dataEquals = dataEquals;
		this.onEvent = onEvent;
	}

	@Override
	public void onAdd(T resource) {
		LOG.debug(() -> resource.getKind() + " " + resource.getMetadata().getName() + " was added in namespace "
				+ resource.getMetadata().getNamespace());
		onEvent.accept(resource);
	}

	@Override
	public void onUpdate(T oldResource, T newResource) {
		LOG.debug(() -> newResource.getKind() + " " + newResource.getMetadata().getName() + " was updated in namespace "
				+ newResource.getMetadata().getNamespace());
		if (dataEquals.test(oldResource, newResource)) {
			LOG.debug(() -> "data in " + newResource.getKind() + " has not changed, will not reload");
		}
		else {
			onEvent.accept(newResource);
		}
	}

	@Override
	public void onDelete(T resource, boolean deletedFinalStateUnknown) {
		LOG.debug(() -> resource.getKind() + " " + resource.getMetadata().getName() + " was deleted in namespace "
				+ resource.getMetadata().getNamespace());
		onEvent.accept(resource);
	}

}

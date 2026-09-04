# Event-Based Change Detector Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the duplicated Kubernetes informer lifecycle and reload flow into one package-private generic base class while preserving both public detector APIs and behavior.

**Architecture:** Add `KubernetesClientEventBasedChangeDetector<T, L>` as the shared lifecycle owner. Keep the ConfigMap and Secret detectors as public resource-specific subclasses that provide their Kubernetes API call, model types, labels, reload metadata, and data comparator.

**Tech Stack:** Java 17, Spring Cloud bootstrap/configuration APIs, Kubernetes Java Client informers, JUnit 5, AssertJ, Mockito, WireMock, Maven.

**Spec:** `docs/superpowers/specs/2026-09-04-event-based-change-detector-design.md`

## Global Constraints

- Preserve both public detector class names and constructor signatures.
- Preserve informer activation, namespace, label-filtering, reload, and shutdown behavior.
- Preserve the existing ConfigMap and Secret activation-log timing difference.
- Preserve ordinary map equality for ConfigMaps and deep byte-array equality for Secrets.
- Do not generalize polling detectors or change auto-configuration.
- Do not run any test, build, or verification command that may execute tests until the user explicitly authorizes that exact run.

## File Map

- Create `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedChangeDetector.java`: shared informer lifecycle, filtering, shutdown, and reload dispatch.
- Modify `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedConfigMapChangeDetector.java`: thin ConfigMap-specific subclass.
- Modify `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedSecretsChangeDetector.java`: thin Secret-specific subclass and deep equality owner.
- Preserve `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesResourceEventHandler.java`: shared event callback behavior; no additional responsibility.
- Use `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedConfigMapChangeDetectorTests.java`: ConfigMap regression coverage.
- Use `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedSecretsChangeDetectorTests.java`: Secret regression and deep-equality coverage.
- Use `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesResourceEventHandlerTests.java`: handler comparator/dispatch coverage.

---

### Task 1: Extract Shared Lifecycle and Migrate ConfigMap

**Files:**

- Create: `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedChangeDetector.java`
- Modify: `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedConfigMapChangeDetector.java`
- Test: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedConfigMapChangeDetectorTests.java`
- Test: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesResourceEventHandlerTests.java`

**Interfaces:**

- Produces: `abstract class KubernetesClientEventBasedChangeDetector<T extends KubernetesObject, L extends KubernetesListObject>`.
- Produces: `protected final void startInformers()` and inherited `void shutdown()` lifecycle methods.
- Produces: `protected abstract Call generateCall(String namespace, CallGeneratorParams params, String labelSelector) throws ApiException`.
- Produces: resource hooks `resourceType()`, `resourceListType()`, `resourceName()`, `labelsPropertyName()`, `reloadTarget()`, and `propertySourceType()`.
- Consumes: `KubernetesResourceEventHandler<T>` using a `BiPredicate<T, T>` and `this::onEvent`.

- [ ] **Step 1: Establish the ConfigMap regression baseline**

Request authorization for this exact focused run:

```bash
./mvnw -pl spring-cloud-kubernetes-client-config \
  -Dtest=KubernetesClientEventBasedConfigMapChangeDetectorTests,KubernetesResourceEventHandlerTests test
```

Expected before refactoring: PASS. If authorization is denied, record the baseline as unexecuted and continue only if the user directs implementation without tests.

- [ ] **Step 2: Create the generic base-class structure**

Create the package-private type with these fields and constructor contract:

```java
abstract class KubernetesClientEventBasedChangeDetector<T extends KubernetesObject, L extends KubernetesListObject>
		extends ConfigurationChangeDetector {

	private static final LogAccessor LOG = new LogAccessor(KubernetesClientEventBasedChangeDetector.class);

	private final ApiClient apiClient;
	private final ConfigurableEnvironment environment;
	private final PropertySourceLocator propertySourceLocator;
	private final Set<String> namespaces;
	private final boolean enableReloadFiltering;
	private final boolean monitoring;
	private final Map<String, String> labels;
	private final List<SharedIndexInformer<T>> informers = new ArrayList<>();
	private final List<SharedInformerFactory> factories = new ArrayList<>();
	private final KubernetesResourceEventHandler<T> handler;

	KubernetesClientEventBasedChangeDetector(ApiClient apiClient, ConfigurableEnvironment environment,
			ConfigurationUpdateStrategy strategy, PropertySourceLocator propertySourceLocator, Set<String> namespaces,
			boolean enableReloadFiltering, boolean monitoring, Map<String, String> labels,
			BiPredicate<T, T> dataEquals) {
		super(strategy);
		this.apiClient = apiClient;
		this.environment = environment;
		this.propertySourceLocator = propertySourceLocator;
		this.namespaces = namespaces;
		this.enableReloadFiltering = enableReloadFiltering;
		this.monitoring = monitoring;
		this.labels = labels;
		this.handler = new KubernetesResourceEventHandler<>(dataEquals, this::onEvent);
	}
}
```

Add these exact abstract hooks:

```java
protected abstract Call generateCall(String namespace, CallGeneratorParams params, String labelSelector)
		throws ApiException;

protected abstract Class<T> resourceType();

protected abstract Class<L> resourceListType();

protected abstract String resourceName();

protected abstract String labelsPropertyName();

protected abstract String reloadTarget();

protected abstract Class<? extends MapPropertySource> propertySourceType();
```

- [ ] **Step 3: Move label filtering and informer startup into the base**

Implement `startInformers()` without changing the current precedence rules:

```java
protected final void startInformers() {
	if (!monitoring) {
		return;
	}

	Map<String, String> labelSelector;
	if (enableReloadFiltering) {
		LOG.warn(() -> "enable reload filtering is deprecated and will be removed in the next major release");
		LOG.warn(() -> "use spring.cloud.kubernetes.reload." + labelsPropertyName() + " instead");
		if (!labels.isEmpty()) {
			LOG.warn(() -> "spring.cloud.kubernetes.reload." + labelsPropertyName() + " is not empty, but "
					+ "spring.cloud.kubernetes.reload.enable-reload-filtering is enabled and will override the former");
		}
		labelSelector = Map.of(ConfigReloadProperties.RELOAD_LABEL_FILTER, "true");
	}
	else {
		labelSelector = labels;
	}

	namespaces.forEach(namespace -> {
		SharedInformerFactory factory = new SharedInformerFactory(apiClient);
		factories.add(factory);
		SharedIndexInformer<T> informer = factory.sharedIndexInformerFor(
				params -> generateCall(namespace, params, labelSelector(labelSelector)), resourceType(), resourceListType());
		LOG.debug(() -> "added " + resourceName() + " informer for namespace : " + namespace + " with labels : "
				+ labelSelector);
		informer.addEventHandler(handler);
		informers.add(informer);
		factory.startAllRegisteredInformers();
	});
}
```

Use the existing static `labelSelector(Map<String, String>)` helper. Do not catch `ApiException`; retain propagation through the Kubernetes client's `CallGenerator` contract.

- [ ] **Step 4: Move shutdown and reload dispatch into the base**

```java
@PreDestroy
void shutdown() {
	informers.forEach(SharedIndexInformer::stop);
	factories.forEach(SharedInformerFactory::stopAllRegisteredInformers);
}

protected void onEvent(KubernetesObject resource) {
	boolean reload = ConfigReloadUtil.reload(reloadTarget(), resource.toString(), propertySourceLocator, environment,
			propertySourceType());
	if (reload) {
		reloadProperties();
	}
}
```

- [ ] **Step 5: Convert the ConfigMap detector into a thin subclass**

Change the declaration to:

```java
public class KubernetesClientEventBasedConfigMapChangeDetector
		extends KubernetesClientEventBasedChangeDetector<V1ConfigMap, V1ConfigMapList> {
```

Keep only `LOG` and `CoreV1Api` as fields. Its constructor keeps the existing signature, assigns `coreV1Api`, and delegates shared values:

```java
super(createApiClientForInformerClient(), environment, strategy, propertySourceLocator,
		namespaces(kubernetesNamespaceProvider, properties, "configmap"), properties.enableReloadFiltering(),
		properties.monitoringConfigMaps(), properties.configMapsLabels(),
		(left, right) -> Objects.equals(left.getData(), right.getData()));
this.coreV1Api = coreV1Api;
```

Preserve ConfigMap activation timing:

```java
@PostConstruct
void inform() {
	if (monitoring()) {
		LOG.info(() -> "Kubernetes event-based configMap change detector activated");
		startInformers();
	}
}
```

Add this method to the base to expose only the flag required for preserving ConfigMap activation-log timing:

```java
protected final boolean monitoring() {
	return monitoring;
}
```

Implement ConfigMap hooks with literal values:

```java
@Override
protected Call generateCall(String namespace, CallGeneratorParams params, String labelSelector) throws ApiException {
	return coreV1Api.listNamespacedConfigMap(namespace)
		.timeoutSeconds(params.timeoutSeconds)
		.resourceVersion(params.resourceVersion)
		.watch(params.watch)
		.labelSelector(labelSelector)
		.buildCall(null);
}

@Override protected Class<V1ConfigMap> resourceType() { return V1ConfigMap.class; }
@Override protected Class<V1ConfigMapList> resourceListType() { return V1ConfigMapList.class; }
@Override protected String resourceName() { return "configmap"; }
@Override protected String labelsPropertyName() { return "config-maps-labels"; }
@Override protected String reloadTarget() { return "config-map"; }
@Override protected Class<? extends MapPropertySource> propertySourceType() {
	return KubernetesClientConfigMapPropertySource.class;
}
```

- [ ] **Step 6: Run focused ConfigMap tests after authorization**

Request authorization again for:

```bash
./mvnw -pl spring-cloud-kubernetes-client-config \
  -Dtest=KubernetesClientEventBasedConfigMapChangeDetectorTests,KubernetesResourceEventHandlerTests test
```

Expected: PASS with zero failures and errors. If denied, use only `git diff --check` and report test verification as not run.

- [ ] **Step 7: Commit Task 1**

```bash
git add \
  spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedChangeDetector.java \
  spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedConfigMapChangeDetector.java
git commit -m "refactor client ConfigMap change detector lifecycle"
```

### Task 2: Migrate the Secret Detector

**Files:**

- Modify: `spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedSecretsChangeDetector.java`
- Test: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedSecretsChangeDetectorTests.java`
- Test: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesResourceEventHandlerTests.java`

**Interfaces:**

- Consumes: the base class and hook signatures created in Task 1.
- Preserves: `static boolean equals(Map<String, byte[]> left, Map<String, byte[]> right)`.

- [ ] **Step 1: Establish the Secret regression baseline**

After explicit authorization, run:

```bash
./mvnw -pl spring-cloud-kubernetes-client-config \
  -Dtest=KubernetesClientEventBasedSecretsChangeDetectorTests,KubernetesResourceEventHandlerTests test
```

Expected before migration: PASS. If not authorized, do not run it.

- [ ] **Step 2: Convert the Secret detector into a thin subclass**

Change the declaration to:

```java
public class KubernetesClientEventBasedSecretsChangeDetector
		extends KubernetesClientEventBasedChangeDetector<V1Secret, V1SecretList> {
```

Keep only `LOG` and `CoreV1Api` as instance infrastructure, plus the existing static equality method. Preserve the public constructor and delegate:

```java
super(createApiClientForInformerClient(), environment, strategy, propertySourceLocator,
		namespaces(kubernetesNamespaceProvider, properties, "secret"), properties.enableReloadFiltering(),
		properties.monitoringSecrets(), properties.secretsLabels(),
		(left, right) -> equals(left.getData(), right.getData()));
this.coreV1Api = coreV1Api;
```

Preserve the unconditional activation log followed by conditional work in the base:

```java
@PostConstruct
void inform() {
	LOG.info(() -> "Kubernetes event-based secrets change detector activated");
	startInformers();
}
```

- [ ] **Step 3: Implement the Secret hooks**

```java
@Override
protected Call generateCall(String namespace, CallGeneratorParams params, String labelSelector) throws ApiException {
	return coreV1Api.listNamespacedSecret(namespace)
		.timeoutSeconds(params.timeoutSeconds)
		.resourceVersion(params.resourceVersion)
		.watch(params.watch)
		.labelSelector(labelSelector)
		.buildCall(null);
}

@Override protected Class<V1Secret> resourceType() { return V1Secret.class; }
@Override protected Class<V1SecretList> resourceListType() { return V1SecretList.class; }
@Override protected String resourceName() { return "secret"; }
@Override protected String labelsPropertyName() { return "secrets-labels"; }
@Override protected String reloadTarget() { return "secrets"; }
@Override protected Class<? extends MapPropertySource> propertySourceType() {
	return KubernetesClientSecretsPropertySource.class;
}
```

Delete the duplicated environment, locator, informer, factory, namespace, monitoring, filtering, labels, handler,
`shutdown()`, and `onEvent()` members. Keep `equals(...)` byte-for-byte unless formatting requires movement.

- [ ] **Step 4: Run focused Secret tests after authorization**

Request authorization again for:

```bash
./mvnw -pl spring-cloud-kubernetes-client-config \
  -Dtest=KubernetesClientEventBasedSecretsChangeDetectorTests,KubernetesResourceEventHandlerTests test
```

Expected: PASS, including all null/empty/deep-byte-array equality cases.

- [ ] **Step 5: Commit Task 2**

```bash
git add spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload/KubernetesClientEventBasedSecretsChangeDetector.java
git commit -m "refactor client Secret change detector lifecycle"
```

### Task 3: Verify Public Compatibility and Integration Behavior

**Files:**

- Inspect: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/VisibleKubernetesClientEventBasedConfigMapChangeDetector.java`
- Inspect: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/VisibleKubernetesClientEventBasedSecretsChangeDetector.java`
- Test: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload_it/EventReloadConfigMapTest.java`
- Test: `spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config/reload_it/EventReloadSecretTest.java`
- Test: `spring-cloud-kubernetes-integration-tests/spring-cloud-kubernetes-k8s-client-reload/src/test/java/org/springframework/cloud/kubernetes/k8s/client/reload/it/K8sClientConfigMapLabelEventTriggeredIT.java`

**Interfaces:**

- Verifies: inherited `protected void onEvent(KubernetesObject)` remains overridable by the two visible test subclasses.
- Verifies: existing constructors remain callable without changes.
- Verifies: the unchanged-data log text remains `data in ConfigMap has not changed, will not reload`.

- [ ] **Step 1: Inspect the final diff for API and behavior drift**

```bash
git diff HEAD~2 -- \
  spring-cloud-kubernetes-client-config/src/main/java/org/springframework/cloud/kubernetes/client/config/reload \
  spring-cloud-kubernetes-client-config/src/test/java/org/springframework/cloud/kubernetes/client/config
git diff --check
```

Confirm explicitly that constructor parameter order, `protected onEvent`, label-property text, reload target strings, model classes, and activation-log placement match the spec.

- [ ] **Step 2: Run the complete client-config unit suite after authorization**

Request authorization for the broader scope before running:

```bash
./mvnw -pl spring-cloud-kubernetes-client-config test
```

Expected: PASS with zero failures and errors.

- [ ] **Step 3: Run the previously failing integration test after separate authorization**

Request authorization for this separate integration scope:

```bash
./mvnw -pl spring-cloud-kubernetes-integration-tests/spring-cloud-kubernetes-k8s-client-reload \
  -Dit.test=K8sClientConfigMapLabelEventTriggeredIT verify
```

Expected: PASS, with the captured output containing `data in ConfigMap has not changed, will not reload`.

- [ ] **Step 4: Commit any verification-driven corrections**

Only if verification required source corrections:

```bash
git add spring-cloud-kubernetes-client-config spring-cloud-kubernetes-integration-tests/spring-cloud-kubernetes-k8s-client-reload
git commit -m "fix event-based detector regression coverage"
```

If no corrections were required, do not create an empty commit.

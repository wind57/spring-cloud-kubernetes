# Event-Based Change Detector Refactoring

## Goal

Remove the duplicated informer lifecycle and reload behavior from
`KubernetesClientEventBasedConfigMapChangeDetector` and
`KubernetesClientEventBasedSecretsChangeDetector` without changing their public APIs or runtime behavior.

## Approach

Introduce a package-private abstract base class named
`KubernetesClientEventBasedChangeDetector<T, L>`, where `T` is a Kubernetes resource and `L` is its Kubernetes list
type. The two existing public detectors remain concrete Spring components and supply only resource-specific details.

This keeps auto-configuration and constructor injection stable while centralizing the lifecycle that is currently
duplicated.

## Shared Base Class

The base class extends `ConfigurationChangeDetector` and owns:

- The informer `ApiClient`.
- The configurable environment and `PropertySourceLocator`.
- The configured namespaces, labels, monitoring flag, and deprecated reload-filtering flag.
- The resource and resource-list classes required by `SharedInformerFactory`.
- The existing `KubernetesResourceEventHandler<T>`.
- The collections of active `SharedIndexInformer<T>` and `SharedInformerFactory` instances.
- Informer creation, registration, startup, and shutdown.
- Label-selector resolution and the existing deprecation warnings.
- Reload detection through `ConfigReloadUtil.reload` and invocation of `reloadProperties()`.

The base constructor receives the values that are already known by each concrete detector: environment, update
strategy, locator, namespace set, labels, flags, resource classes, property-source class, reload target, configuration
property name, and data-equality predicate.

To avoid coupling the base class to individual `CoreV1Api` methods, it defines one package-private functional
interface for producing a Kubernetes watch call from a namespace and `CallGeneratorParams`. Each subclass supplies
that function using its existing `listNamespacedConfigMap` or `listNamespacedSecret` call chain.

## Concrete Detectors

`KubernetesClientEventBasedConfigMapChangeDetector` remains public and keeps its current constructor. It supplies:

- `V1ConfigMap` and `V1ConfigMapList`.
- The ConfigMap watch-call function.
- ConfigMap monitoring and label settings.
- The `Objects.equals` data comparison.
- The `config-map` reload target and `KubernetesClientConfigMapPropertySource` type.

`KubernetesClientEventBasedSecretsChangeDetector` also remains public with its current constructor. It supplies:

- `V1Secret` and `V1SecretList`.
- The Secret watch-call function.
- Secret monitoring and label settings.
- The existing deep byte-array data comparison.
- The `secrets` reload target and `KubernetesClientSecretsPropertySource` type.

The Secret equality helper remains resource-specific. It is not generalized because ConfigMap values use ordinary
string equality while Secret values require `Arrays.equals`.

## Behavior Preservation

The refactoring must preserve:

- No informer startup when monitoring for that resource is disabled.
- One informer factory and informer per configured namespace.
- Existing label selection and deprecated reload-filtering precedence.
- Existing warning and debug messages, including resource-specific configuration property names.
- Add/delete reload dispatch and changed-data update dispatch.
- Suppression of updates whose data has not changed.
- Shutdown of every informer and factory.
- Existing reload target strings and property-source types.

The activation-log placement differs today: ConfigMap logs activation only when monitoring is enabled, while Secret
logs before checking its monitoring flag. This refactoring preserves that observable behavior by leaving activation
logging in the concrete detectors immediately before they delegate to the shared startup method.

## Error Handling

The base class does not introduce new exception handling. The watch-call function follows the same exception contract
used by `SharedInformerFactory`, and failures continue to propagate through the existing informer infrastructure.
Shutdown remains idempotent to the same extent as the current implementations: it iterates over the objects that were
successfully registered.

## Testing

Refactor the existing ConfigMap and Secret detector tests to continue exercising both concrete public classes. Add
focused base-class coverage only where shared behavior cannot be observed through those concrete tests. Preserve the
Secret equality tests because they protect the byte-array comparison that is passed into the generic handler.

The relevant unit and integration tests should be run only when explicitly authorized.

## Non-Goals

- Replacing the two public detectors with one public generic detector.
- Changing auto-configuration or constructor signatures.
- Changing label-filtering semantics or deprecation policy.
- Changing reload behavior, logging text, or activation timing.
- Generalizing unrelated polling change detectors.

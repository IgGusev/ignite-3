/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.configuration;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.ignite.internal.configuration.notifications.ConfigurationNotifier.notifyListeners;
import static org.apache.ignite.internal.configuration.util.ConfigurationUtil.checkConfigurationType;
import static org.apache.ignite.internal.configuration.util.ConfigurationUtil.innerNodeVisitor;
import static org.apache.ignite.internal.configuration.util.ConfigurationUtil.touch;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.ignite.configuration.ConfigurationTree;
import org.apache.ignite.configuration.RootKey;
import org.apache.ignite.configuration.annotation.Config;
import org.apache.ignite.configuration.annotation.ConfigurationRoot;
import org.apache.ignite.configuration.annotation.InternalConfiguration;
import org.apache.ignite.configuration.annotation.PolymorphicConfigInstance;
import org.apache.ignite.configuration.notifications.ConfigurationListener;
import org.apache.ignite.configuration.notifications.ConfigurationNamedListListener;
import org.apache.ignite.configuration.notifications.ConfigurationNotificationEvent;
import org.apache.ignite.configuration.validation.Validator;
import org.apache.ignite.internal.configuration.ConfigurationChanger.ConfigurationUpdateListener;
import org.apache.ignite.internal.configuration.notifications.ConfigurationStorageRevisionListener;
import org.apache.ignite.internal.configuration.notifications.ConfigurationStorageRevisionListenerHolder;
import org.apache.ignite.internal.configuration.storage.ConfigurationStorage;
import org.apache.ignite.internal.configuration.tree.ConfigurationSource;
import org.apache.ignite.internal.configuration.tree.ConfigurationVisitor;
import org.apache.ignite.internal.configuration.tree.ConstructableTreeNode;
import org.apache.ignite.internal.configuration.tree.InnerNode;
import org.apache.ignite.internal.configuration.tree.TraversableTreeNode;
import org.apache.ignite.internal.configuration.util.ConfigurationUtil;
import org.apache.ignite.internal.configuration.util.KeyNotFoundException;
import org.apache.ignite.internal.configuration.validation.ExceptKeysValidator;
import org.apache.ignite.internal.configuration.validation.ImmutableValidator;
import org.apache.ignite.internal.configuration.validation.OneOfValidator;
import org.apache.ignite.internal.configuration.validation.PowerOfTwoValidator;
import org.apache.ignite.internal.configuration.validation.RangeValidator;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.manager.IgniteComponent;
import org.jetbrains.annotations.Nullable;

/**
 * Configuration registry.
 */
public class ConfigurationRegistry implements IgniteComponent, ConfigurationStorageRevisionListenerHolder {
    /** The logger. */
    private static final IgniteLogger LOG = Loggers.forClass(ConfigurationRegistry.class);

    /** Generated configuration implementations. Mapping: {@link RootKey#key} -> configuration implementation. */
    private final Map<String, DynamicConfiguration<?, ?>> configs = new HashMap<>();

    /** Root keys. */
    private final Collection<RootKey<?, ?>> rootKeys;

    /** Configuration change handler. */
    private final ConfigurationChanger changer;

    /** Runtime implementations generator for node classes. */
    private final ConfigurationTreeGenerator generator;

    /** Flag that indicates if the {@link ConfigurationTreeGenerator} instance is owned by this object or not. */
    private boolean ownConfigTreeGenerator = false;

    /** Configuration storage revision change listeners. */
    private final ConfigurationListenerHolder<ConfigurationStorageRevisionListener> storageRevisionListeners =
            new ConfigurationListenerHolder<>();

    /**
     * Constructor.
     *
     * @param rootKeys                    Configuration root keys.
     * @param validators                  Validators.
     * @param storage                     Configuration storage.
     * @param internalSchemaExtensions    Internal extensions ({@link InternalConfiguration}) of configuration schemas ({@link
     *                                    ConfigurationRoot} and {@link Config}).
     * @param polymorphicSchemaExtensions Polymorphic extensions ({@link PolymorphicConfigInstance}) of configuration schemas.
     * @throws IllegalArgumentException If the configuration type of the root keys is not equal to the storage type, or if the schema or its
     *                                  extensions are not valid.
     */
    public ConfigurationRegistry(
            Collection<RootKey<?, ?>> rootKeys,
            Set<Validator<?, ?>> validators,
            ConfigurationStorage storage,
            Collection<Class<?>> internalSchemaExtensions,
            Collection<Class<?>> polymorphicSchemaExtensions
    ) {
        this(
                rootKeys,
                validators,
                storage,
                new ConfigurationTreeGenerator(rootKeys, internalSchemaExtensions, polymorphicSchemaExtensions)
        );

        this.ownConfigTreeGenerator = true;
    }

    /**
     * Constructor.
     *
     * @param rootKeys                    Configuration root keys.
     * @param validators                  Validators.
     * @param storage                     Configuration storage.
     * @throws IllegalArgumentException If the configuration type of the root keys is not equal to the storage type, or if the schema or its
     *                                  extensions are not valid.
     */
    public ConfigurationRegistry(
            Collection<RootKey<?, ?>> rootKeys,
            Set<Validator<?, ?>> validators,
            ConfigurationStorage storage,
            ConfigurationTreeGenerator generator
    ) {
        this.generator = generator;

        checkConfigurationType(rootKeys, storage);

        this.rootKeys = rootKeys;

        Set<Validator<?, ?>> validators0 = new HashSet<>(validators);

        validators0.add(new ImmutableValidator());
        validators0.add(new OneOfValidator());
        validators0.add(new ExceptKeysValidator());
        validators0.add(new PowerOfTwoValidator());
        validators0.add(new RangeValidator());

        changer = new ConfigurationChanger(notificationUpdateListener(), rootKeys, validators0, storage) {
            @Override
            public InnerNode createRootNode(RootKey<?, ?> rootKey) {
                return generator.instantiateNode(rootKey.schemaClass());
            }
        };

        rootKeys.forEach(rootKey -> {
            DynamicConfiguration<?, ?> cfg = generator.instantiateCfg(rootKey, changer);

            configs.put(rootKey.key(), cfg);
        });
    }

    /**
     * Registers default validator implementation to the validators map.
     *
     * @param validators     Validators map.
     * @param annotatopnType Annotation type instance for the validator.
     * @param validator      Validator instance.
     * @param <A>            Annotation type.
     */
    private static <A extends Annotation> void addDefaultValidator(
            Map<Class<? extends Annotation>, Set<Validator<?, ?>>> validators,
            Class<A> annotatopnType,
            Validator<A, ?> validator
    ) {
        validators.computeIfAbsent(annotatopnType, a -> new HashSet<>(1)).add(validator);
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        changer.start();
    }

    /** {@inheritDoc} */
    @Override
    public void stop() throws Exception {
        changer.stop();

        storageRevisionListeners.clear();

        if (ownConfigTreeGenerator) {
            generator.close();
        }
    }

    /**
     * Initializes the configuration storage - reads data and sets default values for missing configuration properties.
     */
    public void initializeDefaults() {
        changer.initializeDefaults();

        for (RootKey<?, ?> rootKey : rootKeys) {
            DynamicConfiguration<?, ?> dynCfg = configs.get(rootKey.key());

            touch(dynCfg);
        }
    }

    /**
     * Gets the public configuration tree.
     *
     * @param rootKey Root key.
     * @param <V>     View type.
     * @param <C>     Change type.
     * @param <T>     Configuration tree type.
     * @return Public configuration tree.
     */
    public <V, C, T extends ConfigurationTree<V, C>> T getConfiguration(RootKey<T, V> rootKey) {
        return (T) configs.get(rootKey.key());
    }

    /**
     * Convert configuration subtree into a user-defined representation.
     *
     * @param path    Path to configuration subtree. Can be empty, can't be {@code null}.
     * @param visitor Visitor that will be applied to the subtree and build the representation.
     * @param <T>     Type of the representation.
     * @return User-defined representation constructed by {@code visitor}.
     * @throws IllegalArgumentException If {@code path} is not found in current configuration.
     */
    public <T> T represent(List<String> path, ConfigurationVisitor<T> visitor) throws IllegalArgumentException {
        SuperRoot superRoot = changer.superRoot();

        Object node;
        try {
            node = ConfigurationUtil.find(path, superRoot, false);
        } catch (KeyNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        if (node instanceof TraversableTreeNode) {
            return ((TraversableTreeNode) node).accept(null, visitor);
        }

        assert node == null || node instanceof Serializable;

        return visitor.visitLeafNode(null, (Serializable) node);
    }

    /**
     * Change configuration.
     *
     * @param changesSrc Configuration source to create patch from it.
     * @return Future that is completed on change completion.
     */
    public CompletableFuture<Void> change(ConfigurationSource changesSrc) {
        return changer.change(changesSrc);
    }

    /**
     * Change configuration. Gives the possibility to atomically update several root trees.
     *
     * @param change Closure that would consume a mutable super root instance.
     * @return Future that is completed on change completion.
     */
    public CompletableFuture<Void> change(Consumer<SuperRootChange> change) {
        return change(new ConfigurationSource() {
            @Override
            public void descend(ConstructableTreeNode node) {
                assert node instanceof SuperRoot : "Descending always starts with super root: " + node;

                SuperRoot superRoot = (SuperRoot) node;

                change.accept(new SuperRootChange() {
                    @Override
                    public <V> V viewRoot(RootKey<? extends ConfigurationTree<V, ?>, V> rootKey) {
                        return Objects.requireNonNull(superRoot.getRoot(rootKey)).specificNode();
                    }

                    @Override
                    public <C> C changeRoot(RootKey<? extends ConfigurationTree<?, C>, ?> rootKey) {
                        // "construct" does a field copying, which is what we need before mutating it.
                        superRoot.construct(rootKey.key(), ConfigurationUtil.EMPTY_CFG_SRC, true);

                        // "rootView" is not re-used here because of return type incompatibility, although code is the same.
                        return Objects.requireNonNull(superRoot.getRoot(rootKey)).specificNode();
                    }
                });
            }
        });
    }

    private ConfigurationUpdateListener notificationUpdateListener() {
        return new ConfigurationUpdateListener() {
            @Override
            public CompletableFuture<Void> onConfigurationUpdated(
                    @Nullable SuperRoot oldSuperRoot, SuperRoot newSuperRoot, long storageRevision, long notificationNumber
            ) {
                var futures = new ArrayList<CompletableFuture<?>>();

                newSuperRoot.traverseChildren(new ConfigurationVisitor<Void>() {
                    @Override
                    public Void visitInnerNode(String key, InnerNode newRoot) {
                        DynamicConfiguration<InnerNode, ?> config = (DynamicConfiguration<InnerNode, ?>) configs.get(key);

                        assert config != null : key;

                        InnerNode oldRoot;

                        if (oldSuperRoot != null) {
                            oldRoot = oldSuperRoot.traverseChild(key, innerNodeVisitor(), true);

                            assert oldRoot != null : key;
                        } else {
                            oldRoot = null;
                        }

                        futures.addAll(notifyListeners(oldRoot, newRoot, config, storageRevision, notificationNumber));

                        return null;
                    }
                }, true);

                futures.addAll(notifyStorageRevisionListeners(storageRevision, notificationNumber));

                return combineFutures(futures);
            }

            @Override
            public CompletableFuture<Void> onRevisionUpdated(long storageRevision, long notificationNumber) {
                return combineFutures(notifyStorageRevisionListeners(storageRevision, notificationNumber));
            }

            private CompletableFuture<Void> combineFutures(Collection<CompletableFuture<?>> futures) {
                if (futures.isEmpty()) {
                    return completedFuture(null);
                }

                CompletableFuture<?>[] resultFutures = futures.stream()
                        // Map futures is only for logging errors.
                        .map(fut -> fut.whenComplete((res, throwable) -> {
                            if (throwable != null) {
                                LOG.info("Failed to notify configuration listener", throwable);
                            }
                        }))
                        .toArray(CompletableFuture[]::new);

                return CompletableFuture.allOf(resultFutures);
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public void listenUpdateStorageRevision(ConfigurationStorageRevisionListener listener) {
        storageRevisionListeners.addListener(listener, changer.notificationCount());
    }

    /** {@inheritDoc} */
    @Override
    public void stopListenUpdateStorageRevision(ConfigurationStorageRevisionListener listener) {
        storageRevisionListeners.removeListener(listener);
    }

    /**
     * Notifies all listeners of the current configuration.
     *
     * <p>{@link ConfigurationListener#onUpdate} and {@link ConfigurationNamedListListener#onCreate} will be called and the value will
     * only be in {@link ConfigurationNotificationEvent#newValue}.
     *
     * @return Future that must signify when processing is completed.
     */
    public CompletableFuture<Void> notifyCurrentConfigurationListeners() {
        return changer.notifyCurrentConfigurationListeners();
    }

    private Collection<CompletableFuture<?>> notifyStorageRevisionListeners(long storageRevision, long notificationNumber) {
        // Lazy init.
        List<CompletableFuture<?>> futures = null;

        for (Iterator<ConfigurationStorageRevisionListener> it = storageRevisionListeners.listeners(notificationNumber); it.hasNext(); ) {
            if (futures == null) {
                futures = new ArrayList<>();
            }

            ConfigurationStorageRevisionListener listener = it.next();

            try {
                CompletableFuture<?> future = listener.onUpdate(storageRevision);

                assert future != null;

                if (future.isCompletedExceptionally() || future.isCancelled() || !future.isDone()) {
                    futures.add(future);
                }
            } catch (Throwable t) {
                futures.add(CompletableFuture.failedFuture(t));
            }
        }

        return futures == null ? List.of() : futures;
    }

    /**
     * Returns the count of configuration listener notifications.
     *
     * <p>Monotonically increasing value that should be incremented each time an attempt is made to notify all listeners of the
     * configuration. Allows to guarantee that new listeners will be called only on the next notification of all configuration listeners.
     */
    public long notificationCount() {
        return changer.notificationCount();
    }
}

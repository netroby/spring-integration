/*
 * Copyright 2016-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.dsl.context;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.support.context.NamedComponent;
import org.springframework.messaging.MessageChannel;
import org.springframework.util.Assert;

/**
 * Standard implementation of {@link IntegrationFlowContext}.
 *
 * @author Artem Bilan
 * @author Gary Russell
 *
 * @since 5.1
 *
 */
public final class StandardIntegrationFlowContext implements IntegrationFlowContext, BeanFactoryAware {

	private final Map<String, IntegrationFlowRegistration> registry = new HashMap<>();

	private ConfigurableListableBeanFactory beanFactory;

	private StandardIntegrationFlowContext() {
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory,
				"To use Spring Integration Java DSL the 'beanFactory' has to be an instance of " +
						"'ConfigurableListableBeanFactory'. " +
						"Consider using 'GenericApplicationContext' implementation.");
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	/**
	 * Associate provided {@link IntegrationFlow} with an {@link StandardIntegrationFlowRegistrationBuilder}
	 * for additional options and farther registration in the application context.
	 * @param integrationFlow the {@link IntegrationFlow} to register
	 * @return the IntegrationFlowRegistrationBuilder associated with the provided {@link IntegrationFlow}
	 */
	@Override
	public StandardIntegrationFlowRegistrationBuilder registration(IntegrationFlow integrationFlow) {
		return new StandardIntegrationFlowRegistrationBuilder(integrationFlow);
	}

	private void register(StandardIntegrationFlowRegistrationBuilder builder) {
		IntegrationFlow integrationFlow = builder.integrationFlowRegistration.getIntegrationFlow();
		String flowId = builder.integrationFlowRegistration.getId();
		if (flowId == null) {
			flowId = generateBeanName(integrationFlow, null);
			builder.id(flowId);
		}
		else if (this.registry.containsKey(flowId)) {
			throw new IllegalArgumentException("An IntegrationFlow '" + this.registry.get(flowId) +
					"' with flowId '" + flowId + "' is already registered.\n" +
					"An existing IntegrationFlowRegistration must be destroyed before overriding.");
		}
		IntegrationFlow theFlow = (IntegrationFlow) registerBean(integrationFlow, flowId, null);
		builder.integrationFlowRegistration.setIntegrationFlow(theFlow);

		final String theFlowId = flowId;
		builder.additionalBeans.forEach((key, value) -> registerBean(key, value, theFlowId));

		if (builder.autoStartup) {
			builder.integrationFlowRegistration.start();
		}
		this.registry.put(flowId, builder.integrationFlowRegistration);
	}

	@SuppressWarnings("unchecked")
	private Object registerBean(Object bean, String beanName, String parentName) {
		if (beanName == null) {
			beanName = generateBeanName(bean, parentName);
		}

		BeanDefinition beanDefinition =
				BeanDefinitionBuilder.genericBeanDefinition((Class<Object>) bean.getClass(), () -> bean)
						.getRawBeanDefinition();

		((BeanDefinitionRegistry) this.beanFactory).registerBeanDefinition(beanName, beanDefinition);

		if (parentName != null) {
			this.beanFactory.registerDependentBean(parentName, beanName);
		}

		return this.beanFactory.getBean(beanName);
	}

	/**
	 * Obtain an {@link IntegrationFlowRegistration} for the {@link IntegrationFlow}
	 * associated with the provided {@code flowId}.
	 * @param flowId the bean name to obtain
	 * @return the IntegrationFlowRegistration for provided {@code id} or {@code null}
	 */
	@Override
	public IntegrationFlowRegistration getRegistrationById(String flowId) {
		return this.registry.get(flowId);
	}

	/**
	 * Destroy an {@link IntegrationFlow} bean (as well as all its dependant beans)
	 * for provided {@code flowId} and clean up all the local cache for it.
	 * @param flowId the bean name to destroy from
	 */
	@Override
	public synchronized void remove(String flowId) {
		if (this.registry.containsKey(flowId)) {
			IntegrationFlowRegistration flowRegistration = this.registry.remove(flowId);
			flowRegistration.stop();

			Arrays.stream(this.beanFactory.getDependentBeans(flowId))
					.forEach(((BeanDefinitionRegistry) this.beanFactory)::removeBeanDefinition);

			((BeanDefinitionRegistry) this.beanFactory).removeBeanDefinition(flowId);
		}
		else {
			throw new IllegalStateException("Only manually registered IntegrationFlows can be removed. "
					+ "But [" + flowId + "] ins't one of them.");
		}
	}

	/**
	 * Obtain a {@link MessagingTemplate} with its default destination set to the input channel
	 * of the {@link IntegrationFlow} for provided {@code flowId}.
	 * <p> Any {@link IntegrationFlow} bean (not only manually registered) can be used for this method.
	 * <p> If {@link IntegrationFlow} doesn't start with the {@link MessageChannel}, the
	 * {@link IllegalStateException} is thrown.
	 * @param flowId the bean name to obtain the input channel from
	 * @return the {@link MessagingTemplate} instance
	 */
	@Override
	public MessagingTemplate messagingTemplateFor(String flowId) {
		return this.registry.get(flowId)
				.getMessagingTemplate();
	}

	/**
	 * Provide the state of the mapping of integration flow names to their
	 * {@link IntegrationFlowRegistration} instances.
	 * @return the registry of flow ids and their registration.
	 */
	@Override
	public Map<String, IntegrationFlowRegistration> getRegistry() {
		return Collections.unmodifiableMap(this.registry);
	}

	private String generateBeanName(Object instance, String parentName) {
		if (instance instanceof NamedComponent && ((NamedComponent) instance).getComponentName() != null) {
			return ((NamedComponent) instance).getComponentName();
		}
		String generatedBeanName = (parentName != null ? parentName : "") + instance.getClass().getName();
		String id = generatedBeanName;
		int counter = -1;
		while (counter == -1 || this.beanFactory.containsBean(id)) {
			counter++;
			id = generatedBeanName + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR + counter;
		}
		return id;
	}

	/**
	 * A Builder pattern implementation for the options to register {@link IntegrationFlow}
	 * in the application context.
	 */
	public final class StandardIntegrationFlowRegistrationBuilder implements IntegrationFlowRegistrationBuilder {

		private final Map<Object, String> additionalBeans = new HashMap<>();

		private final IntegrationFlowRegistration integrationFlowRegistration;

		private boolean autoStartup = true;

		StandardIntegrationFlowRegistrationBuilder(IntegrationFlow integrationFlow) {
			this.integrationFlowRegistration = new StandardIntegrationFlowRegistration(integrationFlow);
			this.integrationFlowRegistration.setBeanFactory(StandardIntegrationFlowContext.this.beanFactory);
			this.integrationFlowRegistration.setIntegrationFlowContext(StandardIntegrationFlowContext.this);
		}

		/**
		 * Specify an {@code id} for the {@link IntegrationFlow} to register.
		 * Must be unique per context.
		 * The registration with this {@code id} must be destroyed before reusing for
		 * a new {@link IntegrationFlow} instance.
		 * @param id the id for the {@link IntegrationFlow} to register
		 * @return the current builder instance
		 */
		@Override
		public StandardIntegrationFlowRegistrationBuilder id(String id) {
			this.integrationFlowRegistration.setId(id);
			return this;
		}

		/**
		 * The {@code boolean} flag to indication if an {@link IntegrationFlow} must be started
		 * automatically after registration. Defaults to {@code true}.
		 * @param autoStartup start or not the {@link IntegrationFlow} automatically after registration.
		 * @return the current builder instance
		 */
		@Override
		public StandardIntegrationFlowRegistrationBuilder autoStartup(boolean autoStartup) {
			this.autoStartup = autoStartup;
			return this;
		}

		/**
		 * Add an object which will be registered as an {@link IntegrationFlow} dependant bean in the
		 * application context. Usually it is some support component, which needs an application context.
		 * For example dynamically created connection factories or header mappers for AMQP, JMS, TCP etc.
		 * @param bean an additional arbitrary bean to register into the application context.
		 * @return the current builder instance
		 */
		@Override
		public StandardIntegrationFlowRegistrationBuilder addBean(Object bean) {
			return addBean(null, bean);
		}

		/**
		 * Add an object which will be registered as an {@link IntegrationFlow} dependant bean in the
		 * application context. Usually it is some support component, which needs an application context.
		 * For example dynamically created connection factories or header mappers for AMQP, JMS, TCP etc.
		 * @param name the name for the bean to register.
		 * @param bean an additional arbitrary bean to register into the application context.
		 * @return the current builder instance
		 */
		@Override
		public StandardIntegrationFlowRegistrationBuilder addBean(String name, Object bean) {
			this.additionalBeans.put(bean, name);
			return this;
		}

		/**
		 * Register an {@link IntegrationFlow} and all the dependant and support components
		 * in the application context and return an associated {@link IntegrationFlowRegistration}
		 * control object.
		 * @return the {@link IntegrationFlowRegistration} instance.
		 */
		@Override
		public IntegrationFlowRegistration register() {
			StandardIntegrationFlowContext.this.register(this);
			return this.integrationFlowRegistration;
		}

	}

}
